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
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.allocation.DataTier;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.cluster.service.MasterServiceTaskQueue;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.Randomness;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportResponseHandler;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * TODO: DOCUMENT ME
 * Runs only on data nodes.
 * Sends event.ingested range to the master node via the TimestampRangeTransportAction
 */
// TODO: maybe rename to TimestampRangeClusterStateService ?
// TODO: other idea: add this functionality to IndicesClusterStateService?
public class EventIngestedRangeClusterStateService extends AbstractLifecycleComponent implements ClusterStateApplier {
    private static final Logger logger = LogManager.getLogger(EventIngestedRangeTask.class);

    private final Settings settings;
    private final ClusterService clusterService;
    private final TransportService transportService;
    private final MasterServiceTaskQueue<EventIngestedRangeClusterStateService.EventIngestedRangeTask> masterServiceTaskQueue;

    public EventIngestedRangeClusterStateService(Settings settings, ClusterService clusterService, TransportService transportService) {
        this.settings = settings;
        this.clusterService = clusterService;
        this.transportService = transportService;
        this.masterServiceTaskQueue = clusterService.createTaskQueue(
            "event-ingested-range-cluster-state-service",
            Priority.NORMAL,
            new TaskExecutor()
        );

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

    private class TaskExecutor implements ClusterStateTaskExecutor<EventIngestedRangeClusterStateService.EventIngestedRangeTask> {
        @Override
        public ClusterState execute(BatchExecutionContext<EventIngestedRangeTask> batchExecutionContext) throws Exception {
            final ClusterState state = batchExecutionContext.initialState();
            for (var taskContext : batchExecutionContext.taskContexts()) {
                EventIngestedRangeTask task = taskContext.getTask();
                if (task instanceof CreateEventIngestedRangeTask rangeTask) {
                    logger.warn("XXX is a CreateEventIngestedRangeTask");
                    ClusterState clusterState = rangeTask.clusterState();
                    Set<String> indicesInClusterState = clusterState.metadata().indices().keySet();
                    logger.warn("XXX indicesInClusterState: " + indicesInClusterState);
                }
                // do something with task
                /*
                 • Look at each shard that is frozen, grabbing the event.ingested min/max
                 • Compare that value to the min/max already in cluster state for that index (?)
                 • Collect a list of all min/max values per index (and shard?) that are not present in cluster state
                 • If that list is non-empty, send a TransportMasterNodeAction (~UpdateEventIngestedRangeAction~)
                   with a ~UpdateEventIngestedRangeActionRequest~ to do the update
                 */

                // MP TODO: LEFTOFF
                UpdateEventIngestedRangeRequest request = new UpdateEventIngestedRangeRequest("index-foo", "[1333-2555]");

                ActionListener<ActionResponse.Empty> execListener = new ActionListener<>() {
                    @Override
                    public void onResponse(ActionResponse.Empty response) {
                        try {
                            logger.warn(
                                "XXX YYY EventIngestedRangeClusterStateService.TaskExecutor.ActionListener onResponse: {}",
                                response
                            );
                        } catch (Exception e) {
                            onFailure(e);
                        }
                    }

                    @Override
                    public void onFailure(Exception e) {
                        logger.warn("XXX YYY EventIngestedRangeClusterStateService.TaskExecutor.ActionListener onFailure: {}", e);
                    }

                    @Override
                    public String toString() {
                        return "My temp exec listener in EventIngestedRangeClusterStateService.TaskExecutor";
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

                // transportService.getLocalNode(),
                // UPDATE_EVENT_INGESTED_RANGE_ACTION_NAME
                // request,
                // new ActionListenerResponseHandler<>(
                // execListener,
                // in -> ActionResponse.Empty.INSTANCE,
                // TransportResponseHandler.TRANSPORT_WORKER
                // )
                // );

            }
            return state;  // TODO: return updated state?
        }

        @Override
        public boolean runOnlyOnMaster() {
            return false;
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

    private record CreateEventIngestedRangeTask(ClusterState clusterState) implements EventIngestedRangeTask {
        @Override
        public void onFailure(Exception e) {
            logger.info("Unable to update event.ingested range in cluster state from index/shard XXX due to error: " + e);
        }
    }

    // this runs on the data nodes
    // MP TODO: create a master-only node and ensure this does not run there
    @Override
    public void applyClusterState(ClusterChangedEvent event) {
        logger.warn("XXX EventIngestedRangeClusterStateService.applyClusterState DEBUG 1");
        // only run this task when a cluster has upgraded to a new version
        if (clusterVersionUpgrade(event)) {

            // TODO: placeholder for fetching all ranges from searchable snapshot (frozen) data nodes
            // TODO: how do I hook up this shard objec to the isFrozenIndex(Settings) method below
            // event.state().routingTable().allShards().filter(shard -> shard.index().set)

            // MP TODO start ---
            List<ShardRouting> primaryShards = event.state()
                .routingTable()
                .allShards()
                .filter(shard -> shard.primary())
                .collect(Collectors.toList());
            logger.warn("XXX EventIngestedRangeClusterStateService.applyClusterState primaryShards size: {}", primaryShards.size());
            for (ShardRouting primaryShard : primaryShards) {
                logger.warn(
                    "XXX EventIngestedRangeClusterStateService.applyClusterState primaryShard index: {}",
                    primaryShard.getIndexName()
                );
            }
            // MP TODO end ---
            boolean submitTask = Randomness.get().nextInt(10) == 4;
            if (event.nodesChanged()
                && event.state().nodes().getMinNodeVersion() == event.state().nodes().getMaxNodeVersion()
                && event.previousState().nodes().getMinNodeVersion() == event.state().nodes().getMinNodeVersion()) {
                logger.warn("This is the actual logic we would use - won't execute for manual testing");
            } else if (submitTask || event.nodesChanged()) {
                logger.warn("XXX Submitting task");
                masterServiceTaskQueue.submitTask(
                    "update-event-ingested-in-cluster-state",
                    new CreateEventIngestedRangeTask(event.state()),
                    TimeValue.MAX_VALUE
                );
            }
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

    // TODO: move this to top of file
    public static final String UPDATE_EVENT_INGESTED_RANGE_ACTION_NAME = "internal:cluster/snapshot/update_event_ingested_range";

    public static final ActionType<ActionResponse.Empty> TYPE = new ActionType<>(UPDATE_EVENT_INGESTED_RANGE_ACTION_NAME);

    // TODO: move the Action class to its own top level class?
    // transport action to send info about updated min/max 'event.ingested' range info to master
    // modelled after SnapshotsService.UpdateSnapshotStatusAction
    public static class UpdateEventIngestedRangeAction extends TransportMasterNodeAction<
        UpdateEventIngestedRangeRequest,
        ActionResponse.Empty> {

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
            logger.warn("XXX YYY: UpdateEventIngestedRangeAction ctor");
        }

        @Override
        protected void masterOperation(
            Task task,
            UpdateEventIngestedRangeRequest request,
            ClusterState state,
            ActionListener<ActionResponse.Empty> listener
        ) {
            logger.warn("XXX YYY UpdateEventIngestedRangeAction.masterOperation would now apply cluster state. Request: {}", request);
            // innerUpdateSnapshotState(
            // request.snapshot(),
            // request.shardId(),
            // null,
            // request.status(),
            // listener.map(v -> ActionResponse.Empty.INSTANCE)
            // );
        }

        @Override
        protected ClusterBlockException checkBlock(UpdateEventIngestedRangeRequest request, ClusterState state) {
            return null;
        }
    }
}
