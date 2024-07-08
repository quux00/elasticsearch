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
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.tasks.CancellableTask;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.tasks.TaskId;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class MyCountActionRequest extends ActionRequest implements IndicesRequest.Replaceable {

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

    @Override
    public Task createTask(long id, String type, String action, TaskId parentTaskId, Map<String, String> headers) {
        return new CancellableTask(id, type, action, "my_count task", parentTaskId, headers);
    }

    @Override
    public String[] indices() {
        return indices.toArray(new String[0]);
    }

    @Override
    public IndicesOptions indicesOptions() {
        return IndicesOptions.DEFAULT;
    }

    @Override
    public IndicesRequest indices(String... indices) {
        this.indices = Arrays.stream(indices).toList();
        return this;
    }
}
