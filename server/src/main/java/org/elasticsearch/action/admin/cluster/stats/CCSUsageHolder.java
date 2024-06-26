/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.admin.cluster.stats;

import org.elasticsearch.telemetry.metric.DoubleHistogram;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Holds a snapshot of the CCS search usage statistics.
 * Used to hold the stats for a single node that's part of a {@link ClusterStatsNodeResponse}, as well as to
 * accumulate stats for the entire cluster and return them as part of the {@link ClusterStatsResponse}.
 */
public class CCSUsageHolder {

    private final LongAdder totalCCSCount;  // TODO: need this? or just sum the successfulSearchTelem and failedSearchTelem ?
    private AtomicReference<SuccessfulSearchTelemetry> successfulSearchTelem;
    private AtomicReference<FailedSearchTelemetry> failedSearchTelem;
    private Map<String, PerRemoteTelemetry> byRemoteCluster;

    public CCSUsageHolder() {
        this.totalCCSCount = new LongAdder();
        this.successfulSearchTelem = new AtomicReference<>(new SuccessfulSearchTelemetry());
        this.failedSearchTelem = new AtomicReference<>(new FailedSearchTelemetry());
        this.byRemoteCluster = new ConcurrentHashMap<>();
    }

    public void updateUsage(CCSUsage ccsUsage) {
        // TODO: fork this to a background thread? if yes, could just pass in the SearchResponse to parse it off the response thread
        doUpdate(ccsUsage);
    }

    private synchronized void doUpdate(CCSUsage ccsUsage) {
        totalCCSCount.increment();
        if (ccsUsage.getFailureType() == null) {
            // handle successful (or partially successful query)
            SuccessfulSearchTelemetry ccsTelemetryInfo = successfulSearchTelem.get();
            ccsTelemetryInfo.update(ccsUsage);

            // process per-remote info
            // TODO: FILL IN ___ LEFTOFF ___

        } else {
            // handle failed query
            FailedSearchTelemetry ccsTelemetryInfo = failedSearchTelem.get();
            ccsTelemetryInfo.update(ccsUsage);
        }
    }

    /**
     * Telemetry of each remote involved in cross cluster searches
     */
    static class PerRemoteTelemetry {
        private String clusterAlias;
        private long count;
        private DoubleHistogram latency;

        PerRemoteTelemetry(String clusterAlias) {
            this.clusterAlias = clusterAlias;
            this.count = 0;
            // TODO: implement DoubleHistogram or DoubleRecorder
        }

        void update(CCSUsage.RemoteClusterUsage remoteUsage) {
            count++;
            latency.record(remoteUsage.getTook());
        }
    }

    static class FailedSearchTelemetry {
        private long count;
        private Map<String, Integer> causes;

        FailedSearchTelemetry() {
            causes = new HashMap<>();
        }

        void update(CCSUsage ccsUsage) {
            count++;
            causes.compute(ccsUsage.getFailureType(), (k, v) -> (v == null) ? 1 : v + 1);
        }
    }

    static class SuccessfulSearchTelemetry {
        private long count; // total number of searches
        private long countMinimizeRoundtrips;
        private long countSearchesWithSkippedRemotes;
        private long countAsync;
        private DoubleHistogram latency;

        SuccessfulSearchTelemetry() {
            this.count = 0;
            this.countMinimizeRoundtrips = 0;
            this.countSearchesWithSkippedRemotes = 0;
            this.countAsync = 0;
            this.latency = new DoubleHistogram() {
                @Override
                public void record(double value) {
                    // MP TODO: FILL IN - what? why do I have to fill this in? How does this thing work?
                }

                @Override
                public void record(double value, Map<String, Object> attributes) {
                    // MP TODO: FILL IN
                }

                @Override
                public String getName() {
                    return "ccs-latency";
                }
            };
        }

        void update(CCSUsage ccsUsage) {
            count++;
            countMinimizeRoundtrips += ccsUsage.isMinimizeRoundTrips() ? 1 : 0;
            countSearchesWithSkippedRemotes += ccsUsage.getSkippedRemotes() > 0 ? 1 : 0;
            countAsync += ccsUsage.isAsync() ? 1 : 0;
            latency.record(ccsUsage.getTook());
        }
    }
}
