/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.execution.scheduler;

import com.facebook.presto.execution.Lifespan;
import com.facebook.presto.execution.RemoteTask;
import com.facebook.presto.execution.SqlStageExecution;
import com.facebook.presto.execution.scheduler.ScheduleResult.BlockedReason;
import com.facebook.presto.metadata.Split;
import com.facebook.presto.operator.StageExecutionStrategy;
import com.facebook.presto.spi.Node;
import com.facebook.presto.spi.connector.ConnectorPartitionHandle;
import com.facebook.presto.split.SplitSource;
import com.facebook.presto.sql.planner.NodePartitionMap;
import com.facebook.presto.sql.planner.plan.PlanNodeId;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.airlift.log.Logger;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntListIterator;

import javax.annotation.concurrent.GuardedBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Supplier;

import static com.facebook.presto.execution.scheduler.SourcePartitionedScheduler.newSourcePartitionedSchedulerAsSourceScheduler;
import static com.facebook.presto.spi.connector.NotPartitionedPartitionHandle.NOT_PARTITIONED;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.airlift.concurrent.MoreFutures.whenAnyComplete;
import static java.util.Objects.requireNonNull;

public class FixedSourcePartitionedScheduler
        implements StageScheduler
{
    private static final Logger log = Logger.get(FixedSourcePartitionedScheduler.class);

    private final SqlStageExecution stage;
    private final NodePartitionMap partitioning;
    private final List<SourceScheduler> sourceSchedulers;
    private final List<ConnectorPartitionHandle> partitionHandles;
    private boolean scheduledTasks;
    private final Optional<LifespanScheduler> groupedLifespanScheduler;

    public FixedSourcePartitionedScheduler(
            SqlStageExecution stage,
            Map<PlanNodeId, SplitSource> splitSources,
            StageExecutionStrategy stageExecutionStrategy,
            List<PlanNodeId> schedulingOrder,
            NodePartitionMap partitioning,
            int splitBatchSize,
            OptionalInt concurrentLifespans,
            NodeSelector nodeSelector,
            List<ConnectorPartitionHandle> partitionHandles)
    {
        requireNonNull(stage, "stage is null");
        requireNonNull(splitSources, "splitSources is null");
        requireNonNull(partitioning, "partitioning is null");
        requireNonNull(partitionHandles, "partitionHandles is null");

        this.stage = stage;
        this.partitioning = partitioning;
        this.partitionHandles = ImmutableList.copyOf(partitionHandles);

        checkArgument(splitSources.keySet().equals(ImmutableSet.copyOf(schedulingOrder)));

        FixedSplitPlacementPolicy splitPlacementPolicy = new FixedSplitPlacementPolicy(nodeSelector, partitioning, stage::getAllTasks);

        ArrayList<SourceScheduler> sourceSchedulers = new ArrayList<>();
        checkArgument(
                partitionHandles.equals(ImmutableList.of(NOT_PARTITIONED)) != stageExecutionStrategy.isAnyScanGroupedExecution(),
                "PartitionHandles should be [NOT_PARTITIONED] if and only if all scan nodes use ungrouped execution strategy");
        int effectiveConcurrentLifespans;
        if (!concurrentLifespans.isPresent() || concurrentLifespans.getAsInt() > partitionHandles.size()) {
            effectiveConcurrentLifespans = partitionHandles.size();
        }
        else {
            effectiveConcurrentLifespans = concurrentLifespans.getAsInt();
        }

        boolean firstPlanNode = true;
        Optional<LifespanScheduler> groupedLifespanScheduler = Optional.empty();
        for (PlanNodeId planNodeId : schedulingOrder) {
            SplitSource splitSource = splitSources.get(planNodeId);
            SourceScheduler sourceScheduler = newSourcePartitionedSchedulerAsSourceScheduler(
                    stage,
                    planNodeId,
                    splitSource,
                    splitPlacementPolicy,
                    Math.max(splitBatchSize / effectiveConcurrentLifespans, 1));

            if (stageExecutionStrategy.isAnyScanGroupedExecution() && !stageExecutionStrategy.isGroupedExecution(planNodeId)) {
                sourceScheduler = new AsGroupedSourceScheduler(sourceScheduler);
            }
            sourceSchedulers.add(sourceScheduler);

            if (firstPlanNode) {
                firstPlanNode = false;
                if (!stageExecutionStrategy.isAnyScanGroupedExecution()) {
                    sourceScheduler.startLifespan(Lifespan.taskWide(), NOT_PARTITIONED);
                }
                else {
                    LifespanScheduler lifespanScheduler = new LifespanScheduler(partitioning, partitionHandles);
                    // Schedule the first few lifespans
                    lifespanScheduler.scheduleInitial(sourceScheduler);
                    // Schedule new lifespans for finished ones
                    stage.addCompletedDriverGroupsChangedListener(lifespanScheduler::onLifespanFinished);
                    groupedLifespanScheduler = Optional.of(lifespanScheduler);
                }
            }
        }
        this.groupedLifespanScheduler = groupedLifespanScheduler;
        this.sourceSchedulers = sourceSchedulers;
    }

    private ConnectorPartitionHandle partitionHandleFor(Lifespan lifespan)
    {
        if (lifespan.isTaskWide()) {
            return NOT_PARTITIONED;
        }
        return partitionHandles.get(lifespan.getId());
    }

    @Override
    public ScheduleResult schedule()
    {
        // schedule a task on every node in the distribution
        List<RemoteTask> newTasks = ImmutableList.of();
        if (!scheduledTasks) {
            OptionalInt totalPartitions = OptionalInt.of(partitioning.getPartitionToNode().size());
            newTasks = partitioning.getPartitionToNode().entrySet().stream()
                    .map(entry -> stage.scheduleTask(entry.getValue(), entry.getKey(), totalPartitions))
                    .collect(toImmutableList());
            scheduledTasks = true;
        }

        boolean allBlocked = true;
        List<ListenableFuture<?>> blocked = new ArrayList<>();
        BlockedReason blockedReason = BlockedReason.NO_ACTIVE_DRIVER_GROUP;

        if (groupedLifespanScheduler.isPresent()) {
            // Start new driver groups on the first scheduler if necessary,
            // i.e. when previous ones have finished execution (not finished scheduling).
            //
            // Invoke schedule method to get a new SettableFuture every time.
            // Reusing previously returned SettableFuture could lead to the ListenableFuture retaining too many listeners.
            blocked.add(groupedLifespanScheduler.get().schedule(sourceSchedulers.get(0)));
        }

        int splitsScheduled = 0;
        Iterator<SourceScheduler> schedulerIterator = sourceSchedulers.iterator();
        List<Lifespan> driverGroupsToStart = ImmutableList.of();
        while (schedulerIterator.hasNext()) {
            SourceScheduler sourceScheduler = schedulerIterator.next();

            for (Lifespan lifespan : driverGroupsToStart) {
                sourceScheduler.startLifespan(lifespan, partitionHandleFor(lifespan));
            }

            ScheduleResult schedule = sourceScheduler.schedule();
            splitsScheduled += schedule.getSplitsScheduled();
            if (schedule.getBlockedReason().isPresent()) {
                blocked.add(schedule.getBlocked());
                blockedReason = blockedReason.combineWith(schedule.getBlockedReason().get());
            }
            else {
                verify(schedule.getBlocked().isDone(), "blockedReason not provided when scheduler is blocked");
                allBlocked = false;
            }

            driverGroupsToStart = sourceScheduler.drainCompletedLifespans();

            if (schedule.isFinished()) {
                stage.schedulingComplete(sourceScheduler.getPlanNodeId());
                schedulerIterator.remove();
                sourceScheduler.close();
            }
        }

        if (allBlocked) {
            return new ScheduleResult(sourceSchedulers.isEmpty(), newTasks, whenAnyComplete(blocked), blockedReason, splitsScheduled);
        }
        else {
            return new ScheduleResult(sourceSchedulers.isEmpty(), newTasks, splitsScheduled);
        }
    }

    @Override
    public void close()
    {
        for (SourceScheduler sourceScheduler : sourceSchedulers) {
            try {
                sourceScheduler.close();
            }
            catch (Throwable t) {
                log.warn(t, "Error closing split source");
            }
        }
        sourceSchedulers.clear();
    }

    public static class FixedSplitPlacementPolicy
            implements SplitPlacementPolicy
    {
        private final NodeSelector nodeSelector;
        private final NodePartitionMap partitioning;
        private final Supplier<? extends List<RemoteTask>> remoteTasks;

        public FixedSplitPlacementPolicy(NodeSelector nodeSelector,
                NodePartitionMap partitioning,
                Supplier<? extends List<RemoteTask>> remoteTasks)
        {
            this.nodeSelector = nodeSelector;
            this.partitioning = partitioning;
            this.remoteTasks = remoteTasks;
        }

        @Override
        public SplitPlacementResult computeAssignments(Set<Split> splits)
        {
            return nodeSelector.computeAssignments(splits, remoteTasks.get(), partitioning);
        }

        @Override
        public void lockDownNodes()
        {
        }

        @Override
        public List<Node> allNodes()
        {
            return ImmutableList.copyOf(partitioning.getPartitionToNode().values());
        }

        public Node getNodeForBucket(int bucketId)
        {
            return partitioning.getPartitionToNode().get(partitioning.getBucketToPartition()[bucketId]);
        }
    }

    private static class LifespanScheduler
    {
        private final Int2ObjectMap<Node> driverGroupToNodeMap;
        private final Map<Node, IntListIterator> nodeToDriverGroupsMap;
        private final List<ConnectorPartitionHandle> partitionHandles;

        private boolean initialScheduled;
        private SettableFuture<?> newDriverGroupReady = SettableFuture.create();
        @GuardedBy("this")
        private final List<Lifespan> recentlyCompletedDriverGroups = new ArrayList<>();

        public LifespanScheduler(NodePartitionMap nodePartitionMap, List<ConnectorPartitionHandle> partitionHandles)
        {
            Map<Node, IntList> nodeToDriverGroupMap = new HashMap<>();
            Int2ObjectMap<Node> driverGroupToNodeMap = new Int2ObjectOpenHashMap<>();
            int[] bucketToPartition = nodePartitionMap.getBucketToPartition();
            Map<Integer, Node> partitionToNode = nodePartitionMap.getPartitionToNode();
            for (int bucket = 0; bucket < bucketToPartition.length; bucket++) {
                int partition = bucketToPartition[bucket];
                Node node = partitionToNode.get(partition);
                nodeToDriverGroupMap.computeIfAbsent(node, key -> new IntArrayList()).add(bucket);
                driverGroupToNodeMap.put(bucket, node);
            }

            this.driverGroupToNodeMap = driverGroupToNodeMap;
            this.nodeToDriverGroupsMap = nodeToDriverGroupMap.entrySet().stream()
                    .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, entry -> entry.getValue().iterator()));
            this.partitionHandles = requireNonNull(partitionHandles, "partitionHandles is null");
        }

        public void scheduleInitial(SourceScheduler scheduler)
        {
            checkState(!initialScheduled);
            initialScheduled = true;

            for (Map.Entry<Node, IntListIterator> entry : nodeToDriverGroupsMap.entrySet()) {
                int driverGroupId = entry.getValue().nextInt();
                scheduler.startLifespan(Lifespan.driverGroup(driverGroupId), partitionHandles.get(driverGroupId));
            }
        }

        public void onLifespanFinished(Iterable<Lifespan> newlyCompletedDriverGroups)
        {
            checkState(initialScheduled);

            synchronized (this) {
                for (Lifespan newlyCompletedDriverGroup : newlyCompletedDriverGroups) {
                    checkArgument(!newlyCompletedDriverGroup.isTaskWide());
                    recentlyCompletedDriverGroups.add(newlyCompletedDriverGroup);
                }
                newDriverGroupReady.set(null);
            }
        }

        public SettableFuture schedule(SourceScheduler scheduler)
        {
            // Return a new future even if newDriverGroupReady has not finished.
            // Returning the same SettableFuture instance could lead to ListenableFuture retaining too many listener objects.

            checkState(initialScheduled);

            List<Lifespan> recentlyCompletedDriverGroups;
            synchronized (this) {
                recentlyCompletedDriverGroups = ImmutableList.copyOf(this.recentlyCompletedDriverGroups);
                this.recentlyCompletedDriverGroups.clear();
                newDriverGroupReady = SettableFuture.create();
            }

            for (Lifespan driverGroup : recentlyCompletedDriverGroups) {
                IntListIterator driverGroupsIterator = nodeToDriverGroupsMap.get(driverGroupToNodeMap.get(driverGroup.getId()));
                if (!driverGroupsIterator.hasNext()) {
                    continue;
                }
                int driverGroupId = driverGroupsIterator.nextInt();
                scheduler.startLifespan(Lifespan.driverGroup(driverGroupId), partitionHandles.get(driverGroupId));
            }

            return newDriverGroupReady;
        }
    }

    private static class AsGroupedSourceScheduler
            implements SourceScheduler
    {
        private final SourceScheduler sourceScheduler;
        private boolean started;
        private boolean completed;
        private final List<Lifespan> pendingCompleted;

        public AsGroupedSourceScheduler(SourceScheduler sourceScheduler)
        {
            this.sourceScheduler = requireNonNull(sourceScheduler, "sourceScheduler is null");
            pendingCompleted = new ArrayList<>();
        }

        @Override
        public ScheduleResult schedule()
        {
            return sourceScheduler.schedule();
        }

        @Override
        public void close()
        {
            sourceScheduler.close();
        }

        @Override
        public PlanNodeId getPlanNodeId()
        {
            return sourceScheduler.getPlanNodeId();
        }

        @Override
        public void startLifespan(Lifespan lifespan, ConnectorPartitionHandle partitionHandle)
        {
            pendingCompleted.add(lifespan);
            if (started) {
                return;
            }
            started = true;
            sourceScheduler.startLifespan(Lifespan.taskWide(), NOT_PARTITIONED);
        }

        @Override
        public List<Lifespan> drainCompletedLifespans()
        {
            if (!completed) {
                List<Lifespan> lifespans = sourceScheduler.drainCompletedLifespans();
                if (lifespans.isEmpty()) {
                    return ImmutableList.of();
                }
                checkState(ImmutableList.of(Lifespan.taskWide()).equals(lifespans));
                completed = true;
            }
            List<Lifespan> result = ImmutableList.copyOf(pendingCompleted);
            pendingCompleted.clear();
            return result;
        }
    }
}
