/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.rest.action.mycount;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.tasks.CancellableTask;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.tasks.TaskId;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class MyCountActionRequest extends ActionRequest {

    private List<String> indices;

    public MyCountActionRequest(List<String> indices) {
        this.indices = indices;
    }

    public MyCountActionRequest(StreamInput in) throws IOException {
        super(in);
        this.indices = in.readStringCollectionAsList();
    }

    public List<String> getIndices() {
        return indices;
    }

    // serialize to the transport layer
    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeStringCollection(indices);
    }

    @Override
    public ActionRequestValidationException validate() {
        return null;
    }

    // add later (after failing rest curl test)
    @Override
    public Task createTask(long id, String type, String action, TaskId parentTaskId, Map<String, String> headers) {
        return new CancellableTask(id, type, action, "my_count task", parentTaskId, headers);
    }
}
