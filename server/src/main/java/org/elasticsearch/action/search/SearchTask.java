/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.search;

import org.elasticsearch.action.support.replication.ReplicationTask;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.tasks.CancellableTask;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Task storing information about a currently running {@link SearchRequest}.
 */
public class SearchTask extends CancellableTask {
    // generating description in a lazy way since source can be quite big
    private final Supplier<String> descriptionSupplier;
    private SearchProgressListener progressListener = SearchProgressListener.NOOP;

    public SearchTask(
        long id,
        String type,
        String action,
        Supplier<String> descriptionSupplier,
        TaskId parentTaskId,
        Map<String, String> headers
    ) {
        super(id, type, action, null, parentTaskId, headers);
        this.descriptionSupplier = descriptionSupplier;
    }

    @Override
    public Status getStatus() {
        if (progressListener == null){
            return null;
        }
        return new Status(progressListener.getProgressStatus());
    }

    public static class Status implements Task.Status {

        private String phase;

        public Status(String phase) {
            this.phase = phase;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field("phase", phase);
            builder.endObject();
            return builder;
        }

        @Override
        public String getWriteableName() {
            return "mp_search_status";
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeString(phase);
        }

        @Override
        public String toString() {
            return Strings.toString(this);
        }

        // Implements equals and hashcode for testing
        @Override
        public boolean equals(Object obj) {
            if (obj == null || obj.getClass() != SearchTask.Status.class) {
                return false;
            }
            SearchTask.Status other = (SearchTask.Status) obj;
            return phase.equals(other.phase);
        }

        @Override
        public int hashCode() {
            return phase.hashCode();
        }
    }

        @Override
    public final String getDescription() {
        return descriptionSupplier.get();
    }

    /**
     * Attach a {@link SearchProgressListener} to this task.
     */
    public final void setProgressListener(SearchProgressListener progressListener) {
        this.progressListener = progressListener;
    }

    /**
     * Return the {@link SearchProgressListener} attached to this task.
     */
    public final SearchProgressListener getProgressListener() {
        return progressListener;
    }

}
