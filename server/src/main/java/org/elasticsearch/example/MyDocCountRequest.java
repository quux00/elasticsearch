/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.example;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;

import java.io.IOException;
import java.util.List;

/**
 * This is a Transport layer object
 */
public class MyDocCountRequest extends ActionRequest {

    public final List<String> indices;

    /**
     * This ctor is used on the coordinator side (? I think)
     */
    public MyDocCountRequest(List<String> indices) {
        this.indices = indices;
    }

    /**
     * This constructor is used on the other side of the Transport send to
     * deserialize from ES wire serialization format back to Java POJO
     */
    public MyDocCountRequest(StreamInput in) throws IOException {
        super(in);
        this.indices = in.readStringList();
    }

    @Override
    public ActionRequestValidationException validate() {
        return null;
    }

    // this writes the request from the coordinator to another node for execution
    // this serializes to the transport layer
    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeStringCollection(indices);
    }
}
