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
import java.util.concurrent.atomic.LongAdder;

/**
 * Holds a snapshot of the CCS search usage statistics.
 * Used to hold the stats for a single node that's part of a {@link ClusterStatsNodeResponse}, as well as to
 * accumulate stats for the entire cluster and return them as part of the {@link ClusterStatsResponse}.
 */
public class CCSUsageHolder {

    private enum CCSTelemetrySearchType {
        SUCCESSFUL, FAILED,
    }

    private final LongAdder totalCCSCount;
    private Map<CCSTelemetrySearchType, CCSTelemetryInfo> telemetryBySearchType;
    private Map<String, CCSTelemetryInfo> byRemoteCluster;

    public CCSUsageHolder() {
        this.totalCCSCount = new LongAdder();
        this.telemetryBySearchType = new ConcurrentHashMap<>();
        this.telemetryBySearchType.put(CCSTelemetrySearchType.SUCCESSFUL, new CCSTelemetryInfo());
        this.telemetryBySearchType.put(CCSTelemetrySearchType.FAILED, new CCSTelemetryInfo());
        this.byRemoteCluster = new ConcurrentHashMap<>();
    }

    public void updateUsage(CCSUsage ccsUsage) {

    }

    static class CCSTelemetryInfo {
        private long count; // total number of searches
        private long countMinimizeRoundtrips;
        private long countSearchesWithSkippedRemotes;
        private long countAsync;
        private DoubleHistogram latency;

        CCSTelemetryInfo() {
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
            }
        }

        public void update(boolean minimizeRoundtrips, boolean skippedRemotes, boolean async, long took) {
            count++;
            countMinimizeRoundtrips += minimizeRoundtrips ? 1 : 0;
            countSearchesWithSkippedRemotes += skippedRemotes ? 1 : 0;
            countAsync += async ? 1 : 0;
            latency.record(took);
        }

    }
}
