/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.admin.cluster.stats;

import java.util.List;
import java.util.Map;

/**
 * This is a snapshot of telemetry from an individual cross-cluster search for _search or _async_search.
 * TODO DOCUMENT ME
 */
// TODO: should this class be called CCSTelemetry and the long term "holder" use the term Usage?
public class CCSUsage {
    private long took;
    private List<String> clusterAliases;  // TODO: this likely needs to be a Map<String, SearchClusterTelemetry> or something like that
    private String failureType;  // TODO: enum?
    private boolean minimizeRoundTrips;
    private boolean async;
    private int skippedRemotes;

    private Map<String, RemoteClusterUsage> perClusterUsage;

    // TODO: probably need a builder class

    public Map<String, RemoteClusterUsage> getPerClusterUsage() {
        return perClusterUsage;
    }

    public int getSkippedRemotes() {
        return skippedRemotes;
    }

    public long getTook() {
        return took;
    }

    public String getFailureType() {
        return failureType;
    }

    public boolean isMinimizeRoundTrips() {
        return minimizeRoundTrips;
    }

    public boolean isAsync() {
        return async;
    }

    public static class RemoteClusterUsage {

        // if MRT=true, the took time on the remote cluster (if MRT=true), otherwise the overall took time
        private long took;

        public RemoteClusterUsage(long took) {
            this.took = took;
        }

        public long getTook() {
            return took;
        }
    }
}
