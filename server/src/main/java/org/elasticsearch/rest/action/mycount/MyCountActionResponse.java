/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.rest.action.mycount;

import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Map;

public class MyCountActionResponse extends ActionResponse implements ToXContentObject {

    private final long count;
    private final Map<String, Long> perIndexCounts;

    public MyCountActionResponse(long count, Map<String, Long> perIndexCounts) {
        this.count = count;
        this.perIndexCounts = perIndexCounts;
    }

    public MyCountActionResponse(StreamInput in) throws IOException {
        super(in);
        this.count = in.readVLong();
        // wrap in TransportVersion check - v3
        this.perIndexCounts = in.readMap(StreamInput::readString, StreamInput::readVLong);
    }

    public long getCount() {
        return count;
    }

    public Map<String, Long> getPerIndexCounts() {
        return perIndexCounts;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVLong(count);
        // wrap in TransportVersion check - v3
        out.writeMap(perIndexCounts, StreamOutput::writeString, StreamOutput::writeVLong);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field("count", count);

        // add in third version
        if (perIndexCounts != null && perIndexCounts.isEmpty() == false) {
            builder.startObject("by-index");
            for (Map.Entry<String, Long> entry : perIndexCounts.entrySet()) {
                builder.field(entry.getKey(), entry.getValue());
            }
            builder.endObject();
        }
        // end add in third version

        builder.endObject();
        return builder;
    }
}
