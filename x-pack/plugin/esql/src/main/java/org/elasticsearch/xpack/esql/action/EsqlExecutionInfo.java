/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.action;

import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.rest.action.RestActions;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ToXContentFragment;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Holds execution metadata about ES|QL queries.
 */
public class EsqlExecutionInfo {
    // for cross-cluster scenarios where cluster names are shown in API responses, use this string
    // rather than empty string (RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY) we use internally
    public static final String LOCAL_CLUSTER_NAME_REPRESENTATION = "(local)";

    public final Map<String, Cluster> clusters;

    public EsqlExecutionInfo() {
        this.clusters = ConcurrentCollections.newConcurrentMap();  // MP TODO: does this need to be a ConcurrentHashMap
    }

    public Cluster getCluster(String clusterAlias) {
        return clusters.get(clusterAlias);
    }

    public void swapCluster(Cluster cluster) {
        // MP TODO: this probably needs to follow the thread safe swapCluster model in SearchResponse, but doing fast-n-easy very for now
        clusters.put(cluster.getClusterAlias(), cluster);
    }

    @Override
    public String toString() {
        return "EsqlExecutionInfo{" + "clusters=" + clusters + '}';
    }

    public static class Cluster implements ToXContentFragment, Writeable {
        public static final ParseField INDICES_FIELD = new ParseField("indices");
        public static final ParseField STATUS_FIELD = new ParseField("status");
        public static final ParseField TOOK = new ParseField("took");

        public static final boolean SKIP_UNAVAILABLE_DEFAULT = true;  // MP TODO - do we need this for ESQL?

        private final String clusterAlias;
        private final String indexExpression; // original index expression from the user for this cluster
        private final boolean skipUnavailable;
        private final Cluster.Status status;
        private final Integer totalShards;
        private final Integer successfulShards;
        private final Integer skippedShards;
        private final Integer failedShards;
        private final List<ShardSearchFailure> failures;
        private final TimeValue took;  // search latency in millis for this cluster sub-search

        /**
         * Marks the status of a Cluster search involved in a Cross-Cluster search.
         */
        public enum Status {
            RUNNING,     // still running
            SUCCESSFUL,  // all shards completed search
            PARTIAL,     // only some shards completed the search, partial results from cluster
            SKIPPED,     // entire cluster was skipped
            FAILED;      // search was failed due to errors on this cluster

            @Override
            public String toString() {
                return this.name().toLowerCase(Locale.ROOT);
            }
        }

        /**
         * Create a Cluster object representing the initial RUNNING state of a Cluster.
         *
         * @param clusterAlias clusterAlias as defined in the remote cluster settings or RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY
         *                     for the local cluster
         * @param indexExpression the original (not resolved/concrete) indices expression provided for this cluster.
         * @param skipUnavailable whether this Cluster is marked as skip_unavailable in remote cluster settings
         */
        public Cluster(String clusterAlias, String indexExpression, boolean skipUnavailable) {
            this(clusterAlias, indexExpression, skipUnavailable, Cluster.Status.RUNNING, null, null, null, null, null, null);
        }

        /**
         * Create a Cluster with a new Status and one or more ShardSearchFailures. This constructor
         * should only be used for fatal failures where shard counters (total, successful, skipped, failed)
         * are not known (unset).
         * @param clusterAlias clusterAlias as defined in the remote cluster settings or RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY
         *                     for the local cluster
         * @param indexExpression the original (not resolved/concrete) indices expression provided for this cluster.
         * @param skipUnavailable whether cluster is marked as skip_unavailable in remote cluster settings
         * @param status current status of the search on this Cluster
         * @param failures list of failures that occurred during the search on this Cluster
         */
        public Cluster(
            String clusterAlias,
            String indexExpression,
            boolean skipUnavailable,
            Cluster.Status status,
            List<ShardSearchFailure> failures
        ) {
            this(clusterAlias, indexExpression, skipUnavailable, status, null, null, null, null, failures, null);
        }

        public Cluster(
            String clusterAlias,
            String indexExpression,
            boolean skipUnavailable,
            Cluster.Status status,
            Integer totalShards,
            Integer successfulShards,
            Integer skippedShards,
            Integer failedShards,
            List<ShardSearchFailure> failures,
            TimeValue took
        ) {
            assert clusterAlias != null : "clusterAlias cannot be null";
            assert indexExpression != null : "indexExpression of Cluster cannot be null";
            assert status != null : "status of Cluster cannot be null";
            this.clusterAlias = clusterAlias;
            this.indexExpression = indexExpression;
            this.skipUnavailable = skipUnavailable;
            this.status = status;
            this.totalShards = totalShards;
            this.successfulShards = successfulShards;
            this.skippedShards = skippedShards;
            this.failedShards = failedShards;
            this.failures = failures == null ? Collections.emptyList() : Collections.unmodifiableList(failures);
            this.took = took;
        }

        public Cluster(StreamInput in) throws IOException {
            this.clusterAlias = in.readString();
            this.indexExpression = in.readString();
            this.status = Cluster.Status.valueOf(in.readString().toUpperCase(Locale.ROOT));
            this.totalShards = in.readOptionalInt();
            this.successfulShards = in.readOptionalInt();
            this.skippedShards = in.readOptionalInt();
            this.failedShards = in.readOptionalInt();
            Long took = in.readOptionalLong();
            if (took == null) {
                this.took = null;
            } else {
                this.took = new TimeValue(took);
            }
            this.failures = Collections.unmodifiableList(in.readCollectionAsList(ShardSearchFailure::readShardSearchFailure));
            this.skipUnavailable = in.readBoolean();
        }

        /**
         * Since the Cluster object is immutable, use this Builder class to create
         * a new Cluster object using the "copyFrom" Cluster passed in and set only
         * changed values.
         *
         * Since the clusterAlias, indexExpression and skipUnavailable fields are
         * never changed once set, this Builder provides no setter method for them.
         * All other fields can be set and override the value in the "copyFrom" Cluster.
         */
        public static class Builder {
            private Cluster.Status status;
            private Integer totalShards;
            private Integer successfulShards;
            private Integer skippedShards;
            private Integer failedShards;
            private List<ShardSearchFailure> failures;
            private TimeValue took;
            private final Cluster original;

            public Builder(Cluster copyFrom) {
                this.original = copyFrom;
            }

            /**
             * @return new Cluster object using the new values passed in via setters
             *         or the values in the "copyFrom" Cluster object set in the
             *         Builder constructor.
             */
            public Cluster build() {
                return new Cluster(
                    original.getClusterAlias(),
                    original.getIndexExpression(),
                    original.isSkipUnavailable(),
                    status != null ? status : original.getStatus(),
                    totalShards != null ? totalShards : original.getTotalShards(),
                    successfulShards != null ? successfulShards : original.getSuccessfulShards(),
                    skippedShards != null ? skippedShards : original.getSkippedShards(),
                    failedShards != null ? failedShards : original.getFailedShards(),
                    failures != null ? failures : original.getFailures(),
                    took != null ? took : original.getTook()
                );
            }

            public Cluster.Builder setStatus(Cluster.Status status) {
                this.status = status;
                return this;
            }

            public Cluster.Builder setTotalShards(int totalShards) {
                this.totalShards = totalShards;
                return this;
            }

            public Cluster.Builder setSuccessfulShards(int successfulShards) {
                this.successfulShards = successfulShards;
                return this;
            }

            public Cluster.Builder setSkippedShards(int skippedShards) {
                this.skippedShards = skippedShards;
                return this;
            }

            public Cluster.Builder setFailedShards(int failedShards) {
                this.failedShards = failedShards;
                return this;
            }

            public Cluster.Builder setFailures(List<ShardSearchFailure> failures) {
                this.failures = failures;
                return this;
            }

            public Cluster.Builder setTook(TimeValue took) {
                this.took = took;
                return this;
            }
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeString(clusterAlias);
            out.writeString(indexExpression);
            out.writeString(status.toString());
            out.writeOptionalInt(totalShards);
            out.writeOptionalInt(successfulShards);
            out.writeOptionalInt(skippedShards);
            out.writeOptionalInt(failedShards);
            out.writeOptionalLong(took == null ? null : took.millis());
            out.writeCollection(failures);
            out.writeBoolean(skipUnavailable);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            String name = clusterAlias;
            if (clusterAlias.equals("")) {
                name = LOCAL_CLUSTER_NAME_REPRESENTATION;
            }
            builder.startObject(name);
            {
                builder.field(STATUS_FIELD.getPreferredName(), getStatus().toString());
                builder.field(INDICES_FIELD.getPreferredName(), indexExpression);
                if (took != null) {
                    builder.field(TOOK.getPreferredName(), took.millis());
                }
                if (totalShards != null) {
                    builder.startObject(RestActions._SHARDS_FIELD.getPreferredName());
                    builder.field(RestActions.TOTAL_FIELD.getPreferredName(), totalShards);
                    if (successfulShards != null) {
                        builder.field(RestActions.SUCCESSFUL_FIELD.getPreferredName(), successfulShards);
                    }
                    if (skippedShards != null) {
                        builder.field(RestActions.SKIPPED_FIELD.getPreferredName(), skippedShards);
                    }
                    if (failedShards != null) {
                        builder.field(RestActions.FAILED_FIELD.getPreferredName(), failedShards);
                    }
                    builder.endObject();
                }
                if (failures != null && failures.size() > 0) {
                    builder.startArray(RestActions.FAILURES_FIELD.getPreferredName());
                    for (ShardSearchFailure failure : failures) {
                        failure.toXContent(builder, params);
                    }
                    builder.endArray();
                }
            }
            builder.endObject();
            return builder;
        }

        public String getClusterAlias() {
            return clusterAlias;
        }

        public String getIndexExpression() {
            return indexExpression;
        }

        public boolean isSkipUnavailable() {
            return skipUnavailable;
        }

        public Cluster.Status getStatus() {
            return status;
        }

        public List<ShardSearchFailure> getFailures() {
            return failures;
        }

        public TimeValue getTook() {
            return took;
        }

        public Integer getTotalShards() {
            return totalShards;
        }

        public Integer getSuccessfulShards() {
            return successfulShards;
        }

        public Integer getSkippedShards() {
            return skippedShards;
        }

        public Integer getFailedShards() {
            return failedShards;
        }

        @Override
        public String toString() {
            return "Cluster{"
                + "alias='"
                + clusterAlias
                + '\''
                + ", status="
                + status
                + ", totalShards="
                + totalShards
                + ", successfulShards="
                + successfulShards
                + ", skippedShards="
                + skippedShards
                + ", failedShards="
                + failedShards
                + ", failures(sz)="
                + failures.size()
                + ", took="
                + took
                + ", indexExpression='"
                + indexExpression
                + '\''
                + ", skipUnavailable="
                + skipUnavailable
                + '}';
        }
    }
}
