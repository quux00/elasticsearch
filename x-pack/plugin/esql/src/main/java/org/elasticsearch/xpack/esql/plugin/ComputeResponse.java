/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plugin;

import org.elasticsearch.TransportVersions;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.compute.operator.DriverProfile;
import org.elasticsearch.transport.TransportResponse;

import java.io.IOException;
import java.util.List;

/**
 * The compute result of {@link DataNodeRequest} or {@link ClusterComputeRequest}
 */
// MP TODO: why was this class marked as final?
public /*final*/ class ComputeResponse extends TransportResponse {
    private final List<DriverProfile> profiles;
    private long tookNanos = 0;
    public int totalShards = 0;
    public int successfulShards = 0;
    public int skippedShards = 0;
    public int failedShards = 0;

    ComputeResponse(List<DriverProfile> profiles) {
        this.profiles = profiles;
    }

    // MP TODO: add the other shards fields here once I get this working
    ComputeResponse(List<DriverProfile> profiles, long tookNanos, int totalShards) {
        this(profiles);
        this.tookNanos = tookNanos;
        this.totalShards = totalShards;
    }

    ComputeResponse(StreamInput in) throws IOException {
        super(in);
        if (in.getTransportVersion().onOrAfter(TransportVersions.V_8_12_0)) {
            if (in.readBoolean()) {
                profiles = in.readCollectionAsImmutableList(DriverProfile::new);
            } else {
                profiles = null;
            }
        } else {
            profiles = null;
        }
        if (in.getTransportVersion().onOrAfter(TransportVersions.INGEST_PIPELINE_EXCEPTION_ADDED)) { // MP TODO: add own new version
            this.tookNanos = in.readVLong();
            this.totalShards = in.readVInt();
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        if (out.getTransportVersion().onOrAfter(TransportVersions.V_8_12_0)) {
            if (profiles == null) {
                out.writeBoolean(false);
            } else {
                out.writeBoolean(true);
                out.writeCollection(profiles);
            }
        }
        if (out.getTransportVersion().onOrAfter(TransportVersions.INGEST_PIPELINE_EXCEPTION_ADDED)) { // MP TODO: add own new version
            out.writeVLong(tookNanos);
            out.writeVInt(totalShards);
        }
    }

    public List<DriverProfile> getProfiles() {
        return profiles;
    }

    public long getTookNanos() {
        return tookNanos;
    }

    public int getTotalShards() {
        return totalShards;
    }
}
