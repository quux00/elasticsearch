/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.mycount;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;

import java.io.IOException;
import java.util.List;

public class MyCountActionRequest extends ActionRequest {

    public List<String> indices;

    public MyCountActionRequest(List<String> indices) {
        this.indices = indices;
    }

    public MyCountActionRequest(StreamInput in) throws IOException {
        super(in);
        this.indices = in.readStringCollectionAsList();
    }

    // serialize to the transport layer
    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeStringCollection(indices);
    }

    public List<String> getIndices() {
        return indices;
    }

    @Override
    public ActionRequestValidationException validate() {
        return null;
    }
}
