/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plugin;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.RefCountingListener;
import org.elasticsearch.compute.operator.DriverProfile;
import org.elasticsearch.compute.operator.FailureCollector;
import org.elasticsearch.compute.operator.ResponseHeadersCollector;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.tasks.CancellableTask;
import org.elasticsearch.transport.RemoteClusterAware;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.esql.action.EsqlExecutionInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A variant of {@link RefCountingListener} with the following differences:
 * 1. Automatically cancels sub tasks on failure.
 * 2. Collects driver profiles from sub tasks.
 * 3. Collects response headers from sub tasks, specifically warnings emitted during compute
 * 4. Collects failures and returns the most appropriate exception to the caller.
 * 5. Updates {@link EsqlExecutionInfo} for display in the response for cross-cluster searches
 */
final class ComputeListener implements Releasable {
    private static final Logger LOGGER = LogManager.getLogger(ComputeService.class);

    private final RefCountingListener refs;
    private final FailureCollector failureCollector = new FailureCollector();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CancellableTask task;
    private final TransportService transportService;
    private final List<DriverProfile> collectedProfiles;
    private final ResponseHeadersCollector responseHeaders;

    private final EsqlExecutionInfo esqlExecutionInfo;
    private final long queryStartTimeNanos;

    private final boolean isTopLevelListenerOnQueryingCluster; // MP FIXME - remove this somehow

    // for use by DataNodeRequestHandler
    public static ComputeListener createComputeListener(
        TransportService transportService,
        CancellableTask task,
        ActionListener<ComputeResponse> delegate
    ) {
        var cl = new ComputeListener(transportService, task, null, null, -1, delegate);
        System.err.println("------- Creating compute Listener for DataNodeRequestHandler: CL id=" + cl.listenerId);
        return cl;
    }

    // for use by top level ComputeListener in ComputeService
    public static ComputeListener createComputeListener(
        TransportService transportService,
        CancellableTask task,
        EsqlExecutionInfo executionInfo,
        long queryStartTimeNanos,
        ActionListener<ComputeResponse> delegate
    ) {
        var computeListener = new ComputeListener(transportService, task, null, executionInfo, queryStartTimeNanos, delegate);
        System.err.println("------- Creating TOP LEVEL compute Listener (non remote): CL id=" + computeListener.listenerId);
        return computeListener;
    }

    /**
     * Create a ComputeListener, specifying a clusterAlias. For use on remote clusters in the ComputeService.ClusterRequestHandler.
     * The final ComputeResponse that is sent back to the querying cluster will have metadata about the search in the ComputeResponse:
     * took time, and shard "accounting" (total, successful, skipped, failed),
     * @param clusterAlias alias of the remote cluster on which the remote query is being done
     * @param transportService
     * @param task
     * @param executionInfo to accumulate metadata about the search
     * @param delegate
     */
    public static ComputeListener createOnRemote(
        String clusterAlias,
        TransportService transportService,
        CancellableTask task,
        EsqlExecutionInfo executionInfo,
        long queryStartTimeNanos,
        ActionListener<ComputeResponse> delegate
    ) {
        var computeListener = new ComputeListener(transportService, task, clusterAlias, executionInfo, queryStartTimeNanos, delegate);
        System.err.printf("------- Creating REMOTE computeListener for [%s]; CL id=[%s]\n", clusterAlias, computeListener.listenerId);
        return computeListener;
    }

    String listenerId = UUID.randomUUID().toString().substring(0, 7);

    private ComputeListener(
        TransportService transportService,
        CancellableTask task,
        String clusterAlias,
        EsqlExecutionInfo executionInfo,
        long queryStartTimeNanos,
        ActionListener<ComputeResponse> delegate
    ) {
        this.transportService = transportService;
        this.task = task;
        this.responseHeaders = new ResponseHeadersCollector(transportService.getThreadPool().getThreadContext());
        this.collectedProfiles = Collections.synchronizedList(new ArrayList<>());
        this.esqlExecutionInfo = executionInfo;
        this.queryStartTimeNanos = queryStartTimeNanos;
        // MP TODO: write assert that checks if that clusterAlias is not null, then executionInfo must not be null

        isTopLevelListenerOnQueryingCluster = clusterAlias == null && executionInfo != null;

        /**
         * Three scenarios:
         * clusterAlias and executionInfo are both null: DataNodeHandler case -
         *   ACTION: do nothing in the refs Listener here, since took time was added in the acquireCompute layer
         * clusterAlias is null, but executionInfo is not: local top level handler -
         *   ACTION: update local cluster status (if present) and overall took time
         * clusterAlias and executionInfo are both non-null: remote ESQL processing -
         *   ACTION: create ComputeResponse with info from the remote ExecutionInfo (already in place)   XX DONE XX
         */

        this.refs = new RefCountingListener(1, ActionListener.wrap(ignored -> {
            responseHeaders.finish();
            ComputeResponse result;

            if (clusterAlias != null) {
                // for remote executions - this ComputeResponse is created on the remote cluster/node and will be serialized back and
                // received by the acquireCompute method callback on the coordinating cluster
                EsqlExecutionInfo.Cluster cluster = esqlExecutionInfo.getCluster(clusterAlias);
                result = new ComputeResponse(
                    collectedProfiles.isEmpty() ? List.of() : collectedProfiles.stream().toList(),
                    cluster.getTook(),
                    cluster.getTotalShards(),
                    cluster.getSuccessfulShards(),
                    cluster.getSkippedShards(),
                    cluster.getFailedShards()
                );
                System.err.printf(
                    "REFS REFS REFS PATH 222: result.id: [%s]; took time into Resp: [%s]; CL id[%s]\n",
                    result.uniqueId,
                    cluster.getTook(),
                    listenerId
                );

            } else {
                result = new ComputeResponse(collectedProfiles.isEmpty() ? List.of() : collectedProfiles.stream().toList());
                System.err.printf("REFS REFS REFS PATH 111: result.id: [%s]; CL id: [%s]\n", result.uniqueId, listenerId);
                if (executionInfo != null /*&& executionInfo.isCrossClusterSearch()*/) {  // MP TODO uncomment later once this works
                    System.err.printf("REFS REFS REFS PATH 111-BBB: result.id: [%s]; CL id: [%s]\n", result.uniqueId, listenerId);
                    EsqlExecutionInfo.Cluster cluster = executionInfo.getCluster(RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY);
                    if (cluster != null) {
                        executionInfo.swapCluster(
                            RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY,
                            (k, v) -> new EsqlExecutionInfo.Cluster.Builder(v).setStatus(EsqlExecutionInfo.Cluster.Status.SUCCESSFUL)
                                .build()
                        );
                    }
                }
            }
            delegate.onResponse(result);
        }, e -> delegate.onFailure(failureCollector.getFailure())));
    }

    /**
     * Acquires a new listener that doesn't collect result
     */
    ActionListener<Void> acquireAvoid() {
        return refs.acquire().delegateResponse((l, e) -> {
            failureCollector.unwrapAndCollect(e);
            try {
                if (cancelled.compareAndSet(false, true)) {
                    LOGGER.debug("cancelling ESQL task {} on failure", task);
                    transportService.getTaskManager().cancelTaskAndDescendants(task, "cancelled on failure", false, ActionListener.noop());
                }
            } finally {
                l.onFailure(e);
            }
        });
    }

    /**
     * Acquires a new listener that collects compute result. This listener will also collect warnings emitted during compute
     */
    ActionListener<ComputeResponse> acquireCompute(String clusterAlias, String callerId) {
        assert clusterAlias == null || (esqlExecutionInfo != null && queryStartTimeNanos > 0)
            : "When clusterAlias is provided to acquireCompute, esqlExecutionInfo must be non-null and queryStartTimeNanos must be positive";

        return acquireAvoid().map(resp -> {
            responseHeaders.collect();
            var profiles = resp.getProfiles();
            if (profiles != null && profiles.isEmpty() == false) {
                collectedProfiles.addAll(profiles);
            }

            if (clusterAlias == null) {  // for the lower level ComputeListener, not top level
                System.err.printf("XXX XXX >>> acquireCompute (DH) isCoord:[%s]; CL id: [%s]; resp: [%s]\n", callerId, listenerId, resp);
                return null;
            }

            if (isTopLevelListenerOnQueryingCluster && clusterAlias.equals(RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY) == false) {
                // this is the callback for the listener to the CCS compute
                System.err.printf(
                    "AAA AAA >>> REMOTE ONLY acquireCompute (CCS) callerId:[%s] response.id: [%s]; CL id: [%s]; resp: [%s]\n",
                    callerId,
                    resp.uniqueId,
                    listenerId,
                    resp
                );
                esqlExecutionInfo.swapCluster(
                    clusterAlias,
                    (k, v) -> new EsqlExecutionInfo.Cluster.Builder(v)
                        // for now ESQL doesn't return partial results, so set status to SUCCESSFUL
                        .setStatus(EsqlExecutionInfo.Cluster.Status.PARTIAL)
                        .setTook(resp.getTook())
                        .setTotalShards(resp.getTotalShards())
                        .setSuccessfulShards(resp.getSuccessfulShards())
                        .setSkippedShards(resp.getSkippedShards())
                        .setFailedShards(resp.getFailedShards())
                        .build()
                );
            } else {
                // callback listener for the local cluster data node and coord completion
                long tookTimeNanos = System.nanoTime() - queryStartTimeNanos;
                TimeValue tookTime = new TimeValue(tookTimeNanos, TimeUnit.NANOSECONDS);
                System.err.printf(
                    "AAA AAA >>> (local) acquireCompute callerId:[%s] took: [%s]; CL id: [%s]; resp: [%s]\n",
                    callerId,
                    tookTime,
                    listenerId,
                    resp
                );
                esqlExecutionInfo.swapCluster(clusterAlias, (k, v) -> {
                    if (v.getTook() == null || v.getTook().nanos() < tookTime.nanos()) {
                        return new EsqlExecutionInfo.Cluster.Builder(v).setTook(tookTime).build();
                    } else {
                        return v;
                    }
                });
            }
            return null;
        });
    }

    /**
     * The ActionListener to be used on the coordinating cluster when sending a cross-cluster
     * compute request to a remote cluster.
     * param clusterAlias clusterAlias of cluster receiving the remote compute request
     * return Listener that will fill in all metadata from to remote cluster into the
     *         {@link EsqlExecutionInfo}  for the clusterAlias cluster.
     */
    // ActionListener<ComputeResponse> acquireCCSCompute(String clusterAlias) {
    // assert clusterAlias != null : "Must provide non-null cluster alias to acquireCompute";
    // assert esqlExecutionInfo != null : "When providing cluster alias to acquireCompute, EsqlExecutionInfo must not be null";
    // return acquireAvoid().map(resp -> {
    // responseHeaders.collect();
    // var profiles = resp.getProfiles();
    // if (profiles != null && profiles.isEmpty() == false) {
    // collectedProfiles.addAll(profiles);
    // }
    // esqlExecutionInfo.swapCluster(
    // clusterAlias,
    // (k, v) -> new EsqlExecutionInfo.Cluster.Builder(v)
    // // for now ESQL doesn't return partial results, so set status to SUCCESSFUL
    // .setStatus(EsqlExecutionInfo.Cluster.Status.SUCCESSFUL)
    // .setTook(resp.getTook())
    // .setTotalShards(resp.getTotalShards())
    // .setSuccessfulShards(resp.getSuccessfulShards())
    // .setSkippedShards(resp.getSkippedShards())
    // .setFailedShards(resp.getFailedShards())
    // .build()
    // );
    // return null;
    // });
    // }

    ActionListener<ComputeResponse> acquireCompute() {
        return acquireCompute(null, "_NO ARGS_");
    }

    // coordinator "cluster-level" reduction time?
    // ActionListener<ComputeResponse> acquireComputeCoord(long queryStartTimeNanosLoc) {
    // return acquireAvoid().map(resp -> {
    // System.err.println("CCC Coord >>> CCC1 ::: resp.uniqueID: " + resp.uniqueId);
    // if (queryStartTimeNanos > 0) {
    // long tookInstanceVar = System.nanoTime() - queryStartTimeNanos;
    // TimeValue tookTimeInstanceVar = new TimeValue(tookInstanceVar, TimeUnit.NANOSECONDS);
    // System.err.println("CCC Coord >>> CCC2 acquireComputeCoord: tookInstanceVar: " + tookTimeInstanceVar);
    // }
    // long tookTimeNanos = System.nanoTime() - queryStartTimeNanosLoc;
    // TimeValue tookOnDataNode = new TimeValue(tookTimeNanos, TimeUnit.NANOSECONDS);
    // System.err.println("CCC Coord >>> CCC3 acquireComputeCoord: tookOnDataNode: " + tookOnDataNode);
    // responseHeaders.collect();
    // var profiles = resp.getProfiles();
    // if (profiles != null && profiles.isEmpty() == false) {
    // collectedProfiles.addAll(profiles);
    // }
    // return null;
    // });
    // }

    /**
     * Acts like {@code acquireCompute} handling the response(s) from the runComputeOnDataNodes
     * phase. Per-cluster took time is recorded in the {@link EsqlExecutionInfo} and status is
     * set to SUCCESSFUL when the CountDown reaches zero.
     * param clusterAlias remote cluster alias the data node compute is running on
     * param queryStartTimeNanosLoc start time (on coordinating cluster) for computing took time (per cluster)
     * param countDown counter of number of data nodes to wait for before changing cluster status from RUNNING to SUCCESSFUL
     */
    // ActionListener<ComputeResponse> acquireComputeForDataNodes(String clusterAlias, long queryStartTimeNanosLoc, CountDown countDown) {
    // assert clusterAlias != null : "Must provide non-null cluster alias to acquireCompute";
    // return acquireAvoid().map(resp -> {
    // responseHeaders.collect();
    // var profiles = resp.getProfiles();
    // if (profiles != null && profiles.isEmpty() == false) {
    // collectedProfiles.addAll(profiles);
    // }
    // long tookTimeNanos = System.nanoTime() - queryStartTimeNanosLoc;
    // TimeValue tookOnDataNode = new TimeValue(tookTimeNanos, TimeUnit.NANOSECONDS);
    // System.err.println("AAA AAA >>> AAA acquireComputeForDataNodes: tookOnDataNode: " + tookOnDataNode);
    // esqlExecutionInfo.swapCluster(clusterAlias, (k, v) -> {
    // EsqlExecutionInfo.Cluster.Builder builder = new EsqlExecutionInfo.Cluster.Builder(v);
    // if (v.getTook() == null || v.getTook().nanos() < tookOnDataNode.nanos()) {
    // builder.setTook(tookOnDataNode);
    // }
    // if (countDown.countDown()) {
    // builder.setStatus(EsqlExecutionInfo.Cluster.Status.SUCCESSFUL);
    // }
    // return builder.build();
    // });
    // return null;
    // });
    // }

    @Override
    public void close() {
        refs.close();
    }
}
