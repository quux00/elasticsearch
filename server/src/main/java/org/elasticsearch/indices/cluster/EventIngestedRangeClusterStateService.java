/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.indices.cluster;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionListenerResponseHandler;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateApplier;
import org.elasticsearch.cluster.ClusterStateTaskExecutor;
import org.elasticsearch.cluster.ClusterStateTaskListener;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.cluster.service.MasterServiceTaskQueue;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.shard.IndexLongFieldRange;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.ShardLongFieldRange;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportResponseHandler;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * TODO LIST (Priority Order)
 *  ✓ Rearrange skeleton to have the Task submission only run on master
 *  ✓ Change code to properly screen for frozen shards only
 *  ✓ Determine how to update cluster state with the new index min/max ranges in TaskExecutor.execute
 *  ¤ Start writing UNIT tests for the above - are there similar unit tests for ShardStateAction.execute?
 *  ¤ Is there a way I can do manual testing of above?
 *  ¤
 *  ¤ Figure out what Writeable data structures need to go into the UpdateEventIngestedRangeRequest - implement that and manual testing
 *  ¤
 *  ¤ Design error handling and bwc - what if the master is older and doesn't have the transport action? (or any other TA error occurs)
 *  ¤ Test with dedicated master (manual)
 *  ¤ Fork shard min/max range lookups to background on data nodes?
 *  ¤ Pull TransportUpdateSettingsAction out to separate class file?
 *  ¤ Any basic unit tests to write? (compare to SnapshotsService and MetadataUpdateSettingsService)
 *  ¤ Start writing IT tests
 *  ¤ Deal with isDedicatedFrozenNode in the doStart check - remove this? Better way to detect if you have frozen indices?
 *  ¤
 *  ¤
 */

/**
 * TODO: DOCUMENT ME
 * Runs only on data nodes.
 * Sends event.ingested range to the master node via the TimestampRangeTransportAction
 */
// TODO: maybe rename to TimestampRangeClusterStateService ?
// TODO: other idea: add this functionality to IndicesClusterStateService?
public class EventIngestedRangeClusterStateService extends AbstractLifecycleComponent implements ClusterStateApplier {
    private static final Logger logger = LogManager.getLogger(EventIngestedRangeClusterStateService.class);

    private final Settings settings;
    private final ClusterService clusterService;
    private final TransportService transportService;
    private final IndicesService indicesService;

    public EventIngestedRangeClusterStateService(
        Settings settings,
        ClusterService clusterService,
        TransportService transportService,
        IndicesService indicesService
    ) {
        this.settings = settings;
        this.clusterService = clusterService;
        this.transportService = transportService;
        this.indicesService = indicesService;

        logger.warn("XXX EventIngestedRangeClusterStateService constructor");
    }

    @Override
    protected void doStart() {
        logger.warn(
            "XXX EventIngestedRangeClusterStateService.doStart called: canContainData: {}; dedicated frozen: {}",
            DiscoveryNode.canContainData(settings),
            DiscoveryNode.isDedicatedFrozenNode(settings)
        );

        // MP TODO: somewhere I saw an isFrozen(DiscoveryNode node) method but I didn't know how to get the local DiscoveryNode
        // MP TODO: -> get that from transportService.getLocalNode(); now I don't remember where the isFrozen(DN) method was - look for it
        // MP TODO: UPDATE: "transportService.getLocalNode().canContainData()" gets NPE since getLocalNode() returns null

        // only run this service on data nodes (for example, master has no frozen shards to access)
        // TODO: is DiscoveryNode.isDedicatedFrozenNode(settings) too restrictive?
        // TODO: what is the best way to limit this to nodes that have a frozen shards?
        if (DiscoveryNode.canContainData(settings) && DiscoveryNode.isDedicatedFrozenNode(settings)) {
            logger.warn("XXX doStart 1");
            clusterService.addStateApplier(this);
        } else {
            logger.warn("XXX doStart 2");
            clusterService.addStateApplier(this);  // MP TODO: remove after manual testing done
        }
    }

    @Override
    protected void doStop() {
        if (DiscoveryNode.canContainData(settings)) {
            clusterService.removeApplier(this);
        }
    }

    @Override
    protected void doClose() throws IOException {}

    /**
     * Runs on data nodes.
     * If a cluster version upgrade is detected (e.g., moving from 8.15.0 to 8.16.0), then a Task
     * will be launched to determine whether any searchable snapshot shards "owned" by this
     * data node need to have their 'event.ingested' min/max range updated in cluster state.
     */
    @Override
    public void applyClusterState(ClusterChangedEvent event) {
        // only run this task when a cluster has upgraded to a new version
        if (clusterVersionUpgrade(event)) {
            Iterator<ShardRouting> shardRoutingIterator = event.state()
                .getRoutingNodes()
                .node(event.state().nodes().getLocalNodeId())
                .iterator();

            logger.warn(
                "XXX EventIngestedRangeClusterStateService.applyClusterState DEBUG 2. Created iterator: {}",
                shardRoutingIterator.hasNext()
            );

            List<ShardRouting> shardsForLookup = new ArrayList<>();
            while (shardRoutingIterator.hasNext()) {
                ShardRouting shardRouting = shardRoutingIterator.next();
                Settings indexSettings = event.state().metadata().index(shardRouting.index()).getSettings();
                if (isFrozenIndex(indexSettings)) {
                    shardsForLookup.add(shardRouting);
                }
            }

            logger.warn("XXX EventIngestedRangeClusterStateService.applyClusterState DEBUG 3. shardsForLookup: {}", shardsForLookup.size());

            Map<Index, List<ShardRangeInfo>> eventIngestedRangeMap = new HashMap<>();

            // MP TODO - bogus entry to have something for initial testing -- start
            Index mpidx = new Index("mpidx", UUID.randomUUID().toString());
            List<ShardRangeInfo> shardRangeList = new ArrayList<>();
            shardRangeList.add(new ShardRangeInfo(new ShardId(mpidx, 22), ShardLongFieldRange.UNKNOWN));
            eventIngestedRangeMap.put(mpidx, shardRangeList);
            // MP TODO - bogus entry to have something for initial testing -- end

            // TODO: this min/max lookup logic likely needs to be forked to background (how do I do that?)

            // TODO: create new list or map here of shards/indexes and new min/max range to update
            for (ShardRouting shardRouting : shardsForLookup) {
                IndexService indexService = indicesService.indexService(shardRouting.index());
                IndicesClusterStateService.Shard shard = indexService.getShardOrNull(shardRouting.shardId().id());
                ShardLongFieldRange shardEventIngestedRange = shard.getEventIngestedRange();
                IndexMetadata indexMetadata = event.state().metadata().index(shardRouting.index());
                if (indexMetadata != null) {  // TODO: I don't think we need this null check long term
                    IndexLongFieldRange clusterStateEventIngestedRange = indexMetadata.getEventIngestedRange();

                    // MP TODO: is this the right check for whether to get min/max range from a shard?
                    if (clusterStateEventIngestedRange.containsAllShardRanges()) {
                        if (shardEventIngestedRange.getMax() > clusterStateEventIngestedRange.getMax()) {
                            logger.warn("XXX: max in shard > max in cluster state - send update for index {}", shardRouting.index());
                        } else if (shardEventIngestedRange.getMin() < clusterStateEventIngestedRange.getMin()) {
                            logger.warn("XXX: min in shard < min in cluster state - send update for index {}", shardRouting.index());
                        }
                    }
                }
            }

            // TODO: need to wrap the code below in an if check - only send if any new info to update on master

            // TODO: need proper Writable collection of new per-index ranges to send in the request

            UpdateEventIngestedRangeRequest request = new UpdateEventIngestedRangeRequest(eventIngestedRangeMap);

            ActionListener<ActionResponse.Empty> execListener = new ActionListener<>() {
                @Override
                public void onResponse(ActionResponse.Empty response) {
                    try {
                        logger.warn("XXX YYY TaskExecutor.ActionListener onResponse: {}", response);
                    } catch (Exception e) {
                        onFailure(e);
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    logger.warn("XXX YYY TaskExecutor.ActionListener onFailure: {}", e);
                }

                @Override
                public String toString() {
                    return "My temp exec listener in TaskExecutor";
                }
            };

            logger.warn("XXX About to send to Master {}. request: {}", UPDATE_EVENT_INGESTED_RANGE_ACTION_NAME, request);
            transportService.sendRequest(
                transportService.getLocalNode(),
                UPDATE_EVENT_INGESTED_RANGE_ACTION_NAME,
                request,
                new ActionListenerResponseHandler<>(
                    execListener.safeMap(r -> null),
                    in -> ActionResponse.Empty.INSTANCE,
                    TransportResponseHandler.TRANSPORT_WORKER
                )
            );
        }
    }

    /**
     * Determine whether a cluster version upgrade has just happened.
     */
    private boolean clusterVersionUpgrade(ClusterChangedEvent event) {
        logger.warn("XXX clusterVersionUpgrade: event.nodesChanged(): " + event.nodesChanged());
        logger.warn("XXX clusterVersionUpgrade: event.state().nodes().getMinNodeVersion(): " + event.state().nodes().getMinNodeVersion());
        logger.warn("XXX clusterVersionUpgrade: event.state().nodes().getMaxNodeVersion(): " + event.state().nodes().getMaxNodeVersion());
        // return event.nodesChanged() &&
        // event.state().nodes().getMinNodeVersion() == event.state().nodes().getMaxNodeVersion() &&
        // event.previousState().nodes().getMinNodeVersion() == event.state().nodes().getMinNodeVersion();
        return event.nodesChanged() || event.nodesRemoved();  // MP FIXME
    }

    // copied from FrozenUtils - should that one move to core/server rather than be in xpack?
    public static boolean isFrozenIndex(Settings indexSettings) {
        return true;  // MP FIXME
        // String tierPreference = DataTier.TIER_PREFERENCE_SETTING.get(indexSettings);
        // List<String> preferredTiers = DataTier.parseTierList(tierPreference);
        // if (preferredTiers.isEmpty() == false && preferredTiers.get(0).equals(DataTier.DATA_FROZEN)) {
        // assert preferredTiers.size() == 1 : "frozen tier preference must be frozen only";
        // return true;
        // } else {
        // return false;
        // }
    }

    public static class ShardRangeInfo extends TransportRequest {
        final ShardId shardId;
        final ShardLongFieldRange eventIngestedRange;

        ShardRangeInfo(StreamInput in) throws IOException {
            super(in);
            this.shardId = new ShardId(in);
            this.eventIngestedRange = ShardLongFieldRange.readFrom(in);
        }

        public ShardRangeInfo(ShardId shardId, ShardLongFieldRange eventIngestedRange) {
            this.shardId = shardId;
            this.eventIngestedRange = eventIngestedRange;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            shardId.writeTo(out);
            eventIngestedRange.writeTo(out);
        }

        @Override
        public String toString() {
            return Strings.format(
                "EventIngestedRangeService.ShardRangeInfo{shardId [%s], eventIngestedRange [%s]}",
                shardId,
                eventIngestedRange
            );
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ShardRangeInfo that = (ShardRangeInfo) o;
            return shardId.equals(that.shardId) && eventIngestedRange.equals(that.eventIngestedRange);
        }

        @Override
        public int hashCode() {
            return Objects.hash(shardId, eventIngestedRange);
        }
    }

    // TODO: move this to top of file or to its own class
    public static final String UPDATE_EVENT_INGESTED_RANGE_ACTION_NAME = "internal:cluster/snapshot/update_event_ingested_range";

    public static final ActionType<ActionResponse.Empty> TYPE = new ActionType<>(UPDATE_EVENT_INGESTED_RANGE_ACTION_NAME);

    // TODO: move the Action class to its own top level class?
    // transport action to send info about updated min/max 'event.ingested' range info to master
    // modelled after SnapshotsService.UpdateSnapshotStatusAction
    // TransportMasterNodeAction ensures this will run on the master node
    public static class UpdateEventIngestedRangeAction extends TransportMasterNodeAction<
        UpdateEventIngestedRangeRequest,
        ActionResponse.Empty> {

        private final MasterServiceTaskQueue<EventIngestedRangeTask> masterServiceTaskQueue;

        @Inject
        public UpdateEventIngestedRangeAction(
            TransportService transportService,
            ClusterService clusterService,
            ThreadPool threadPool,
            ActionFilters actionFilters,
            IndexNameExpressionResolver indexNameExpressionResolver  // MP TODO: hmm - probably don't need this?
        ) {
            super(
                UPDATE_EVENT_INGESTED_RANGE_ACTION_NAME,
                false,
                transportService,
                clusterService,
                threadPool,
                actionFilters,
                UpdateEventIngestedRangeRequest::new,
                indexNameExpressionResolver,
                in -> ActionResponse.Empty.INSTANCE,
                EsExecutors.DIRECT_EXECUTOR_SERVICE
            );

            this.masterServiceTaskQueue = clusterService.createTaskQueue(
                "event-ingested-range-cluster-state-service",
                Priority.NORMAL,
                new TaskExecutor()
            );

            // TODO: Hmm, I'm seeing this created on data-only nodes - is that OK?
            logger.warn("XXX YYY: UpdateEventIngestedRangeAction ctor");
        }

        // MP TODO: why is this method passed ClusterState? what is it allowed to do? Can it update cluster state?
        @Override
        protected void masterOperation(
            Task task,
            UpdateEventIngestedRangeRequest request,
            ClusterState state,
            ActionListener<ActionResponse.Empty> listener
        ) {
            logger.warn("XXX YYY UpdateEventIngestedRangeAction.masterOperation NOW SUBMITTING TASK. Request: {}", request);

            masterServiceTaskQueue.submitTask(
                "update-event-ingested-in-cluster-state",
                new CreateEventIngestedRangeTask(request),
                TimeValue.MAX_VALUE
            );
        }

        @Override
        protected ClusterBlockException checkBlock(UpdateEventIngestedRangeRequest request, ClusterState state) {
            return null;
        }

        // runs on the master node only (called from masterOperation of UpdateEventIngestedRangeAction
        public static class TaskExecutor implements ClusterStateTaskExecutor<UpdateEventIngestedRangeAction.EventIngestedRangeTask> {
            @Override
            public ClusterState execute(BatchExecutionContext<EventIngestedRangeTask> batchExecutionContext) throws Exception {
                List<TaskContext<EventIngestedRangeTask>> tasksToBeApplied = new ArrayList<>();
                ClusterState state = batchExecutionContext.initialState();
                final Map<Index, IndexLongFieldRange> updatedEventIngestedRanges = new HashMap<>();
                for (var taskContext : batchExecutionContext.taskContexts()) {
                    EventIngestedRangeTask task = taskContext.getTask();
                    if (task instanceof CreateEventIngestedRangeTask rangeTask) {
                        logger.warn(
                            "XXX YYY TaskExecutor.execute called now UPDATE cluster state. Request: {}",
                            rangeTask.rangeUpdateRequest()
                        );

                        Map<Index, List<ShardRangeInfo>> rangeMap = rangeTask.rangeUpdateRequest().getEventIngestedRangeMap();

                        for (Map.Entry<Index, List<ShardRangeInfo>> entry : rangeMap.entrySet()) {
                            Index index = entry.getKey();
                            List<ShardRangeInfo> shardRangeList = entry.getValue();

                            IndexMetadata indexMetadata = state.getMetadata().index(index);
                            IndexLongFieldRange currentEventIngestedRange = updatedEventIngestedRanges.get(index);
                            if (currentEventIngestedRange == null && indexMetadata != null) {
                                currentEventIngestedRange = indexMetadata.getEventIngestedRange();
                            }
                            // is this guaranteed to be not null? - it will be UNKNOWN if not set in cluster state (?), but for safety ...
                            if (currentEventIngestedRange == null) {
                                // TODO: having unknown here does not work when you do extendWithShardRange - how is this supposed to work?
                                currentEventIngestedRange = IndexLongFieldRange.UNKNOWN;
                            }
                            IndexLongFieldRange newEventIngestedRange = currentEventIngestedRange;
                            for (ShardRangeInfo shardRange : shardRangeList) {
                                newEventIngestedRange = currentEventIngestedRange.extendWithShardRange(
                                    shardRange.shardId.id(),
                                    1, // indexMetadata.getNumberOfShards(),
                                    shardRange.eventIngestedRange
                                );
                            }

                            // TODO: or does this need to use .equals rather than == ??
                            if (newEventIngestedRange != currentEventIngestedRange) {
                                updatedEventIngestedRanges.put(index, newEventIngestedRange);
                            }
                        }
                    }
                }
                if (updatedEventIngestedRanges.size() > 0) {
                    Metadata.Builder metadataBuilder = Metadata.builder(state.metadata());
                    for (Map.Entry<Index, IndexLongFieldRange> entry : updatedEventIngestedRanges.entrySet()) {
                        Index index = entry.getKey();
                        IndexLongFieldRange range = entry.getValue();
                        metadataBuilder.put(IndexMetadata.builder(metadataBuilder.getSafe(index)).eventIngestedRange(range));
                    }

                    // MP TODO: Hmm, not sure this should be inside the for loop - it is NOT in ShardStateAction.execute :-(
                    // can you just (re)build state like this iteratively and have it work? // TODO: need to have a test for this
                    state = ClusterState.builder(state).metadata(metadataBuilder).build();
                }

                for (var taskContext : batchExecutionContext.taskContexts()) {
                    taskContext.success(() -> {}); // TODO: need an error handler to call taskContext.onFailure() ??
                }
                return state;
            }

            @Override
            public boolean runOnlyOnMaster() {
                return true;
            }

            @Override
            public void clusterStatePublished(ClusterState newClusterState) {
                // TODO: need this?
            }

            @Override
            public String describeTasks(List<EventIngestedRangeTask> tasks) {
                // TODO: override this or just use the default?
                return ClusterStateTaskExecutor.super.describeTasks(tasks);
            }
        }

        interface EventIngestedRangeTask extends ClusterStateTaskListener {}

        record CreateEventIngestedRangeTask(UpdateEventIngestedRangeRequest rangeUpdateRequest) implements EventIngestedRangeTask {
            @Override
            public void onFailure(Exception e) {
                if (e != null) {
                    logger.info(
                        "Unable to update event.ingested range in cluster state from index/shard XXX due to error: {}: {}",
                        e.getMessage(),
                        e
                    );
                }

            }
        }
    }
}
