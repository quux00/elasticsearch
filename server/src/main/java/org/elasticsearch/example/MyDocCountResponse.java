/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.example;

import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Map;

/**
 * This is a Transport layer object
 */
public class MyDocCountResponse extends ActionResponse implements ToXContent {
    private final long count;
    private final Map<String, Long> countPerNode;

    public MyDocCountResponse(long count, Map<String, Long> perNodeCount) {
        this.count = count;
        this.countPerNode = perNodeCount;
    }

    public MyDocCountResponse(StreamInput in) throws IOException {
        super(in);
        this.count = in.readVLong();
        this.countPerNode = in.readMap(StreamInput::readString, StreamInput::readVLong);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVLong(count);
        out.writeMap(countPerNode, StreamOutput::writeString, StreamOutput::writeVLong);
    }

    public long getCount() {
        return count;
    }

    public Map<String, Long> getCountPerNode() {
        return countPerNode;
    }

    public RestStatus status() {
        return RestStatus.OK;  // placeholder
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field("my_doc_count", getCount());

        builder.startArray("per_node_doc_counts");
        for (Map.Entry<String, Long> entry : getCountPerNode().entrySet()) {
            builder.startObject();
            builder.field("node", entry.getKey());
            builder.field("doc_count", entry.getValue());
            builder.endObject();
        }
        builder.endArray();

        builder.endObject();
        return builder;
    }
}
