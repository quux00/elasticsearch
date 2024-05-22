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
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.allocation.DataTier;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.cluster.service.MasterServiceTaskQueue;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.shard.IndexLongFieldRange;
import org.elasticsearch.index.shard.ShardLongFieldRange;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportResponseHandler;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
    private final ClusterService clusterService; // TODO: is this still needed?
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
        logger.warn("XXX EventIngestedRangeClusterStateService.applyClusterState DEBUG 1");
        // only run this task when a cluster has upgraded to a new version
        if (clusterVersionUpgrade(event)) {
            Iterator<ShardRouting> shardRoutingIterator = event.state()
                .getRoutingNodes()
                .node(event.state().nodes().getLocalNodeId())
                .iterator();

            logger.warn("XXX EventIngestedRangeClusterStateService.applyClusterState DEBUG 2. Created iterator: {}",
                shardRoutingIterator.hasNext());

            List<ShardRouting> shardsForLookup = new ArrayList<>();
            while (shardRoutingIterator.hasNext()) {
                ShardRouting shardRouting = shardRoutingIterator.next();
                Settings indexSettings = event.state().metadata().index(shardRouting.index()).getSettings();
                if (isFrozenIndex(indexSettings)) {
                    shardsForLookup.add(shardRouting);
                }
            }

            logger.warn("XXX EventIngestedRangeClusterStateService.applyClusterState DEBUG 3. shardsForLookup: {}",
                shardsForLookup.size());

            // TODO: this min/max lookup logic likely needs to be forked to background (how do I do that?)

            // TODO: create new list or map here of shards/indexes and new min/max range to update
            for (ShardRouting shardRouting : shardsForLookup) {
                IndexService indexService = indicesService.indexService(shardRouting.index());
                IndicesClusterStateService.Shard shard = indexService.getShardOrNull(shardRouting.shardId().id());
                ShardLongFieldRange shardEventIngestedRange = shard.getEventIngestedRange();
                IndexMetadata indexMetadata = event.state().metadata().index(shardRouting.index());
                IndexLongFieldRange clusterStateEventIngestedRange = indexMetadata.getEventIngestedRange();

                // TODO: is this the right check for whether to get min/max range from a shard?
                if (clusterStateEventIngestedRange.containsAllShardRanges()) {
                    if (shardEventIngestedRange.getMax() > clusterStateEventIngestedRange.getMax()) {
                        logger.warn("XXX: max in shard > max in cluster state - send update for index {}", shardRouting.index());
                    } else if (shardEventIngestedRange.getMin() < clusterStateEventIngestedRange.getMin()) {
                        logger.warn("XXX: min in shard < min in cluster state - send update for index {}", shardRouting.index());
                    }
                }
            }

            // TODO: need to wrap the code below in an if check - only send if any new info to update on master

            // TODO: need proper Writable collection of new per-index ranges to send in the request
            UpdateEventIngestedRangeRequest request = new UpdateEventIngestedRangeRequest("index-foo", "[1333-2555]");

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
        logger.warn(
            "XXX clusterVersionUpgrade: event.previousState().nodes().getMinNodeVersion(): "
                + event.previousState().nodes().getMinNodeVersion()
        );
        // return event.nodesChanged() &&
        // event.state().nodes().getMinNodeVersion() == event.state().nodes().getMaxNodeVersion() &&
        // event.previousState().nodes().getMinNodeVersion() == event.state().nodes().getMinNodeVersion();
        return true;  // MP FIXME
    }

    // copied from FrozenUtils - should that one move to core/server rather than be in xpack?
    public static boolean isFrozenIndex(Settings indexSettings) {
        String tierPreference = DataTier.TIER_PREFERENCE_SETTING.get(indexSettings);
        List<String> preferredTiers = DataTier.parseTierList(tierPreference);
        if (preferredTiers.isEmpty() == false && preferredTiers.get(0).equals(DataTier.DATA_FROZEN)) {
            assert preferredTiers.size() == 1 : "frozen tier preference must be frozen only";
            return true;
        } else {
            return false;
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
        private class TaskExecutor implements ClusterStateTaskExecutor<UpdateEventIngestedRangeAction.EventIngestedRangeTask> {
            @Override
            public ClusterState execute(BatchExecutionContext<EventIngestedRangeTask> batchExecutionContext) throws Exception {
                final ClusterState state = batchExecutionContext.initialState();
                for (var taskContext : batchExecutionContext.taskContexts()) {
                    EventIngestedRangeTask task = taskContext.getTask();
                    if (task instanceof CreateEventIngestedRangeTask rangeTask) {
                        logger.warn(
                            "XXX YYY TaskExecutor.execute called would now UPDATE cluster state. Request: {}",
                            rangeTask.rangeUpdateRequest()
                        );
                    }
                }
                return state;  // TODO: return updated state?
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

        private record CreateEventIngestedRangeTask(UpdateEventIngestedRangeRequest rangeUpdateRequest) implements EventIngestedRangeTask {
            @Override
            public void onFailure(Exception e) {
                logger.info("Unable to update event.ingested range in cluster state from index/shard XXX due to error: " + e);
            }
        }
    }
}
