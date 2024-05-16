/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.indices.cluster;

import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.support.master.MasterNodeRequest;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.core.TimeValue;

import java.io.IOException;

// modelled after UpdateIndexShardSnapshotStatusRequest

/**
 * Internal request that is used to send changes in event.ingested min/max cluster state ranges (per index) to master
 */
public class UpdateEventIngestedRangeRequest extends MasterNodeRequest<UpdateEventIngestedRangeRequest> {

    private final String index;  // TODO: may want Index class here
    private final String newRange; // TODO: do we want ShardLongFieldRange or IndexLongFieldRange or ??

    // note: neither of the FieldRange classs have index as an instance var, so if use it, it needs to part of some Map -
    // can you send top level maps over the wire (transport layer)?
    // IndexLongFieldRange does implement Writeable
    // ShardLongFieldRange also implements Writeable

    protected UpdateEventIngestedRangeRequest(String index, String newRange) {
        super(TimeValue.MAX_VALUE); // By default, keep trying to post updates to avoid snapshot processes getting stuck // TODO: this ok?
        this.index = index;
        this.newRange = newRange;
    }

    protected UpdateEventIngestedRangeRequest(StreamInput in) throws IOException {
        // TODO: likely need put TransportVersion guards here like we did for _resolve/cluster?
        super(in);
        this.index = in.readString();
        this.newRange = in.readString();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        // TODO: likely need put TransportVersion guards here like we did for _resolve/cluster?
        out.writeString(index);
        out.writeString(newRange);
    }

    @Override
    public ActionRequestValidationException validate() {
        return null;
    }

    @Override
    public String getDescription() {
        return "update event.ingested min/max range request task"; // MP TODO: remove this?
    }

    @Override
    public String toString() {
        return "UpdateEventIngestedRangeRequest{" + "index=[" + index + ']' + ", newRange=[" + newRange + ']' + '}';
    }
}
