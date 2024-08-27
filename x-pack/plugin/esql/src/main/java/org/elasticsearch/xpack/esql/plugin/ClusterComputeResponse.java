/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plugin;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.compute.operator.DriverProfile;

import java.io.IOException;
import java.util.List;

public class ClusterComputeResponse extends ComputeResponse {

    // MP TODO: make all these final
    private long tookNanos;
    public int totalShards;
    public int successfulShards;
    public int skippedShards;
    public int failedShards;

    public ClusterComputeResponse(
        List<DriverProfile> profiles,
        long tookNanos,
        int totalShards,
        int successfulShards,
        int skippedShards,
        int failedShards
    ) {
        super(profiles);
        this.tookNanos = tookNanos;
        this.totalShards = totalShards;
        this.successfulShards = successfulShards;
        this.skippedShards = skippedShards;
        this.failedShards = failedShards;
    }

    public ClusterComputeResponse(StreamInput in) throws IOException {
        super(in);
        // MP TODO: do I need a TransportVersion check here?
        // this.tookNanos = in.readVLong();
        // this.totalShards = in.readVInt();
        // this.successfulShards = in.readVInt();
        // this.skippedShards = in.readVInt();
        // this.failedShards = in.readVInt();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        // MP TODO: do I need a TransportVersion check here?
        // out.writeVLong(this.tookNanos);
        // out.writeVInt(this.totalShards);
        // out.writeVInt(this.successfulShards);
        // out.writeVInt(this.skippedShards);
        // out.writeVInt(this.failedShards);
    }

    public long getTookNanos() {
        return tookNanos;
    }

    public int getTotalShards() {
        return totalShards;
    }

    public int getSuccessfulShards() {
        return successfulShards;
    }

    public int getSkippedShards() {
        return skippedShards;
    }

    public int getFailedShards() {
        return failedShards;
    }
}
