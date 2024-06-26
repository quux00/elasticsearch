/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.admin.cluster.stats;

import org.HdrHistogram.DoubleHistogram;

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
        System.err.println("XXX YYY DEBUG A doUpdate");
        totalCCSCount.increment();
        if (ccsUsage.getFailureType() == null) {
            System.err.println("XXX YYY DEBUG B");
            // handle successful (or partially successful query)
            SuccessfulSearchTelemetry ccsTelemetryInfo = successfulSearchTelem.get();
            ccsTelemetryInfo.update(ccsUsage);
            System.err.println("XXX YYY DEBUG C1: successful  latency max: " + ccsTelemetryInfo.latency.getMaxValue());
            System.err.println("XXX YYY DEBUG C2: successful  latency p50: " + ccsTelemetryInfo.latency.getValueAtPercentile(50));
            System.err.println("XXX YYY DEBUG C3: successful  latency p90: " + ccsTelemetryInfo.latency.getValueAtPercentile(90));
            System.err.println("XXX YYY DEBUG C4: successful  latency avg: " + ccsTelemetryInfo.latency.getMean());

            // process per-remote info
            // TODO: FILL IN ___ LEFTOFF ___

        } else {
            // handle failed query
            FailedSearchTelemetry ccsTelemetryInfo = failedSearchTelem.get();
            ccsTelemetryInfo.update(ccsUsage);
            System.err.println("XXX YYY DEBUG D");
        }

        System.err.println("XXX YYY DEBUG E: totalCCSCount: " + totalCCSCount.longValue());
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
            // TODO: what should we use for num significant value digits?
            latency = new DoubleHistogram(2);
        }

        void update(CCSUsage.RemoteClusterUsage remoteUsage) {
            count++;
            latency.recordValue(remoteUsage.getTook()); // do I need to add count as well using recordValueWithCount?
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
            this.latency = new DoubleHistogram(2);
        }

        void update(CCSUsage ccsUsage) {
            count++;
            countMinimizeRoundtrips += ccsUsage.isMinimizeRoundTrips() ? 1 : 0;
            countSearchesWithSkippedRemotes += ccsUsage.getSkippedRemotes() > 0 ? 1 : 0;
            countAsync += ccsUsage.isAsync() ? 1 : 0;
            latency.recordValue(ccsUsage.getTook());
            System.err.println("XXX ZZZ DEBUG 80: countAsync: " + countAsync);
            System.err.println("XXX ZZZ DEBUG 81: countSearchesWithSkippedRemotes: " + countSearchesWithSkippedRemotes);
            System.err.println("XXX ZZZ DEBUG 82: minRT count: " + countMinimizeRoundtrips);
        }
    }
}
