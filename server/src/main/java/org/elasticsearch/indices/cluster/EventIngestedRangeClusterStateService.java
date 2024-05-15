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
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateApplier;
import org.elasticsearch.cluster.ClusterStateTaskExecutor;
import org.elasticsearch.cluster.ClusterStateTaskListener;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.allocation.DataTier;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.cluster.service.MasterServiceTaskQueue;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.settings.Settings;

import java.io.IOException;
import java.util.List;

/**
 * TODO: DOCUMENT ME
 * Runs only on data nodes.
 * Sends event.ingested range to the master node via the TimestampRangeTransportAction
 */
// TODO: maybe rename to TimestampRangeClusterStateService ?
// TODO: other idea: add this functionality to IndicesClusterStateService?
public class EventIngestedRangeClusterStateService extends AbstractLifecycleComponent implements ClusterStateApplier {

    private static final Logger logger = LogManager.getLogger(EventIngestedRangeTask.class);

    private final MasterServiceTaskQueue<EventIngestedRangeClusterStateService.EventIngestedRangeTask> masterServiceTaskQueue;
    private final Settings settings;


    public EventIngestedRangeClusterStateService(Settings settings, ClusterService clusterService) {
        this.settings = settings;
        this.masterServiceTaskQueue = clusterService.createTaskQueue("event-ingested-range-cluster-state-service", Priority.NORMAL,
            new TaskExecutor());
    }

    @Override
    protected void doStart() {
        if (DiscoveryNode.isMasterNode(settings)) {  // or similar logic to check this is a data node
            return;
        }
    }

//    private void submitCreateSnapshotRequest(
//        CreateSnapshotRequest request,
//        ActionListener<Snapshot> listener,
//        Repository repository,
//        Snapshot snapshot,
//        RepositoryMetadata initialRepositoryMetadata
//    ) {
//        repository.getRepositoryData(
//            EsExecutors.DIRECT_EXECUTOR_SERVICE, // Listener is lightweight, only submits a cluster state update task, no need to fork
//            listener.delegateFailure(
//                (l, repositoryData) -> masterServiceTaskQueue.submitTask(
//                    "create_snapshot [" + snapshot.getSnapshotId().getName() + ']',
//                    new SnapshotsService.CreateSnapshotTask(repository, repositoryData, l, snapshot, request, initialRepositoryMetadata),
//                    request.masterNodeTimeout()
//                )
//            )
//        );
//    }

    private class TaskExecutor implements ClusterStateTaskExecutor<EventIngestedRangeClusterStateService.EventIngestedRangeTask> {
        @Override
        public ClusterState execute(BatchExecutionContext<EventIngestedRangeTask> batchExecutionContext) throws Exception {
            final ClusterState state = batchExecutionContext.initialState();
            for (var taskContext : batchExecutionContext.taskContexts()) {
                logger.info("EvIngested.TaskExecute: task: " + taskContext.toString());
                EventIngestedRangeTask task = taskContext.getTask();
                // do something with task
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

    private record CreateEventIngestedRangeTask(String someArgsHere) implements EventIngestedRangeTask {
        @Override
        public void onFailure(Exception e) {
            logger.info("Unable to update event.ingested range in cluster state from index/shard XXX bcs YYY");
        }
    }

    @Override
    public void applyClusterState(ClusterChangedEvent event) {
        // only run this task when a cluster has upgraded to a new version
        if (event.nodesChanged() &&
            event.state().nodes().getMinNodeVersion() == event.state().nodes().getMaxNodeVersion() &&
            event.previousState().nodes().getMinNodeVersion() == event.state().nodes().getMinNodeVersion()) {



            // TODO: placeholder for fetching all ranges from searchable snapshot (frozen) data nodes
            // TODO: how do I hook up this shard objec to the isFrozenIndex(Settings) method below
            event.state().routingTable().allShards().filter(shard -> shard.index().set)

            masterServiceTaskQueue.submitTask("update-event-ingested-in-cluster-state", null,  // MP FIXME - submit a real task
                null);   // MP TODO: what should the timeout be set to?
        }
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


    @Override
    protected void doStop() {

    }

    @Override
    protected void doClose() throws IOException {

    }
}
