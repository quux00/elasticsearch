/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.mycount;

import org.elasticsearch.TransportVersions;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Map;

public class MyCountActionResponse extends ActionResponse implements ToXContentObject {

    private final long count;
    private final Map<String, Long> countByIndex;

    public MyCountActionResponse(long count, Map<String, Long> countByIndex) {
        this.count = count;
        this.countByIndex = countByIndex;
    }

    public MyCountActionResponse(StreamInput in) throws IOException {
        super(in);
        this.count = in.readVLong();
        if (in.getTransportVersion().onOrAfter(TransportVersions.MY_COUNT_BY_INDEX_COUNTS)) {
            this.countByIndex = in.readMap(StreamInput::readString, StreamInput::readVLong);
        } else {
            this.countByIndex = null;
        }
    }

    public long getCount() {
        return count;
    }

    public Map<String, Long> getCountByIndex() {
        return countByIndex;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVLong(count);
        if (out.getTransportVersion().onOrAfter(TransportVersions.MY_COUNT_BY_INDEX_COUNTS)) {
            out.writeMap(countByIndex, StreamOutput::writeString, StreamOutput::writeVLong);
        }
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();  // "{"
        builder.field("count", count);
        if (countByIndex != null && countByIndex.isEmpty() == false) {
            builder.startObject("by-index"); // {
            for (Map.Entry<String, Long> entry : countByIndex.entrySet()) {
                builder.field(entry.getKey(), entry.getValue());
            }
            builder.endObject();
        }
        builder.endObject();
        return builder;
    }
}
