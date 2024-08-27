/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plugin;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.RefCountingListener;
import org.elasticsearch.common.Strings;
import org.elasticsearch.compute.operator.DriverProfile;
import org.elasticsearch.compute.operator.FailureCollector;
import org.elasticsearch.compute.operator.ResponseHeadersCollector;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.tasks.CancellableTask;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.esql.action.EsqlExecutionInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A variant of {@link RefCountingListener} with the following differences:
 * 1. Automatically cancels sub tasks on failure.
 * 2. Collects driver profiles from sub tasks.
 * 3. Collects response headers from sub tasks, specifically warnings emitted during compute
 * 4. Collects failures and returns the most appropriate exception to the caller.
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
    private final EsqlExecutionInfo executionInfo;

    ComputeListener(
        TransportService transportService,
        CancellableTask task,
        EsqlExecutionInfo executionInfo,
        ActionListener<ComputeResponse> delegate
    ) {
        this.transportService = transportService;
        this.task = task;
        this.executionInfo = executionInfo;
        this.responseHeaders = new ResponseHeadersCollector(transportService.getThreadPool().getThreadContext());
        this.collectedProfiles = Collections.synchronizedList(new ArrayList<>());
        this.refs = new RefCountingListener(1, ActionListener.wrap(ignored -> {
            responseHeaders.finish();
            var result = new ComputeResponse(collectedProfiles.isEmpty() ? List.of() : collectedProfiles.stream().toList());
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
     * Acquires a new listener that collects compute result. This listener will also collects warnings emitted during compute
     */
    ActionListener<ComputeResponse> acquireCompute() {
        return acquireAvoid().map(resp -> {
            int numProfiles = 0;
            if (resp.getProfiles() != null) {
                numProfiles = resp.getProfiles().size();
            }
            // MP TODO --- start TMP
            if (resp.remoteAddress() == null) {
                System.err.println("NNN NNN DEBUG NNN: acquireCompute resp for port: null + num profiles: " + numProfiles);
            } else {
                System.err.println(
                    "NNN NNN DEBUG NNN: acquireCompute resp for port: [["
                        + resp.remoteAddress().getPort()
                        + "]] :: numProfiles: "
                        + numProfiles
                );
            }
            for (DriverProfile profile : resp.getProfiles()) {
                System.err.println("  |-- NNN NNN DEBUG NNN: acquireCompute resp profile: " + Strings.toString(profile));
            }
            // MP TODO --- end TMP
            responseHeaders.collect();
            var profiles = resp.getProfiles();
            if (profiles != null && profiles.isEmpty() == false) {
                collectedProfiles.addAll(profiles);
            }
            System.err.println("NNN NNN DEBUG NNN: acquireCompute resp collectedProfiles sz: " + collectedProfiles.size());
            return null;
        });
    }

    /**
     * Acquire a listener that will update EsqlExecutionInfo with info specific to a query on the 'clusterAlias' remote cluster
     * @param clusterAlias remote cluster alias that this listener will handle
     */
    ActionListener<ComputeResponse> acquireCompute(String clusterAlias) {
        assert clusterAlias != null : "Must provide non-null cluster alias to acquireCompute";
        assert executionInfo != null : "When providing cluster alias to acquireCompute, EsqlExecutionInfo must not be null";
        return acquireAvoid().map(resp -> {
            var profiles = resp.getProfiles();
            int numProfiles = profiles == null ? -1 : profiles.size();
            // MP TODO: is this the right way to calculate overall took time for the query on the remote cluster?
            long maxTook = -1;
            for (DriverProfile profile : profiles) {
                maxTook = Math.max(maxTook, profile.tookNanos());
                // MP TODO: is there anything in various operators that is useful here? Not clear to me on first glance anything is.
                // for (DriverStatus.OperatorStatus operator : profile.operators()) {
                // Operator.Status operatorStatus = operator.status();
                // }
            }
            // MP TODO: if we get here does that mean that the remote search is finished and was SUCCESSFUL?
            // MP TODO: if yes, where does the failure path go - how do we update the ExecutionInfo with failure info?
            System.err.printf(
                ">>> ^^^ DEBUG 777: ComputeListener acquireCompute - add cluster info for [%s] to execInfo. nprofiles: %d; maxTook: %d; "
                    + "type of resp:%s; is ClusterComputeResponse:%s\n",
                clusterAlias,
                numProfiles,
                maxTook,
                resp.getClass().getSimpleName(),
                resp instanceof ClusterComputeResponse
            );
            EsqlExecutionInfo.Cluster cluster = executionInfo.getCluster(clusterAlias);
            executionInfo.swapCluster(
                new EsqlExecutionInfo.Cluster.Builder(cluster)
                    // MP TODO: how detect actual response status to set Cluster status here?
                    .setStatus(EsqlExecutionInfo.Cluster.Status.SUCCESSFUL)
                    .setTook(new TimeValue(maxTook))
                    .build()
            );
            responseHeaders.collect();
            if (profiles != null && profiles.isEmpty() == false) {
                collectedProfiles.addAll(profiles);
            }
            return null;
        });
    }

    @Override
    public void close() {
        refs.close();
    }
}
