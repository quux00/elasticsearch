/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.search;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.search.TotalHits;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.transport.RemoteClusterAware;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * DOCUMENT ME
 */
public class CrossClusterSearchProgressListener extends SearchProgressListener {

    private static final Logger logger = LogManager.getLogger(CrossClusterSearchProgressListener.class);

    private boolean ccsMinimizeRoundtrips;

    /**
     * Executed when shards are ready to be queried.
     *
     * @param shards The list of shards to query.
     * @param skipped The list of skipped shards.
     * @param clusters The statistics for remote clusters included in the search.
     * @param fetchPhase <code>true</code> if the search needs a fetch phase, <code>false</code> otherwise.
     **/
    public void onListShards(List<SearchShard> shards, List<SearchShard> skipped, SearchResponse.Clusters clusters, boolean fetchPhase) {
        logger.warn("XXX SSS CCSProgListener onListShards: shards size: {}; shards: {}", shards.size(), shards);
        logger.warn("XXX SSS CCSProgListener onListShards: skipped size: {}; skipped: {}", skipped.size(), skipped);
        logger.warn("XXX SSS CCSProgListener onListShards: clusters: " + clusters);
        logger.warn("XXX SSS CCSProgListener onListShards: fetchPhase: " + fetchPhase);
        ccsMinimizeRoundtrips = clusters.isCcsMinimizeRoundtrips();

        if (ccsMinimizeRoundtrips == false) {
            // Partition by clusterAlias and get counts
            Map<String, Integer> totalByClusterAlias = Stream.concat(shards.stream(), skipped.stream())
                .collect(Collectors.groupingBy(shard -> {
                    String clusterAlias = shard.clusterAlias();
                    return clusterAlias != null ? clusterAlias : RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY;
                }, Collectors.reducing(0, e -> 1, Integer::sum)));
            Map<String, Integer> skippedByClusterAlias = skipped.stream().collect(Collectors.groupingBy(shard -> {
                String clusterAlias = shard.clusterAlias();
                return clusterAlias != null ? clusterAlias : RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY;
            }, Collectors.reducing(0, e -> 1, Integer::sum)));

            for (Map.Entry<String, Integer> entry : totalByClusterAlias.entrySet()) {
                String clusterAlias = entry.getKey();
                AtomicReference<SearchResponse.Cluster> clusterRef = clusters.getCluster(clusterAlias);
                if (clusterRef.get().getTotalShards() != null) {
                    // this cluster has already had initial state set by SearchShards API
                    continue;
                }

                final Integer totalCount = entry.getValue();
                System.err.println("XXX SSS CCSProgListener onListShards totalCount for " + clusterAlias + " = " + totalCount);
                /// MP TODO I don't know where this skipped list is really coming from - does it deal with SearchShards API results?
                /// MP TODO Is this called post local can-match?
                final Integer skippedCount = skippedByClusterAlias.get(clusterAlias);
                System.err.println("XXX SSS CCSProgListener onListShards skippedCount for " + clusterAlias + " = " + skippedCount);

                boolean swapped;
                do {
                    SearchResponse.Cluster curr = clusterRef.get();
                    SearchResponse.Cluster.Status status = curr.getStatus();
                    assert status == SearchResponse.Cluster.Status.RUNNING
                        : "should have RUNNING status during onListShards but has " + status;
                    Integer currSkippedShards = curr.getSkippedShards();
                    System.err.printf(
                        "XXX SSS CCSProgListener currSkippedShards = %s; incoming skippedCount = %s\n",
                        currSkippedShards,
                        skippedCount
                    );
                    // assert ((currSkippedShards == null) || (curr.getSkippedShards().equals(skippedCount))) :
                    // "currSkippedShards " + curr.getSkippedShards() + " and skippedCount = " + skippedCount;
                    SearchResponse.Cluster updated = new SearchResponse.Cluster(
                        curr.getClusterAlias(),
                        curr.getIndexExpression(),
                        curr.isSkipUnavailable(),
                        status,
                        entry.getValue(),
                        0,
                        skippedByClusterAlias.get(clusterAlias) == null ? 0 : skippedByClusterAlias.get(clusterAlias),
                        0,
                        curr.getFailures(),
                        null,
                        false  /// MP TODO: need to deal with timed_out in MRT=false - how do that?
                    );
                    swapped = clusterRef.compareAndSet(curr, updated);
                    logger.warn("XXX SSS CCSProgListener onListShards DEBUG 66 swapped: {} ;; new cluster: {}", swapped, updated);
                } while (swapped == false);

            }
        }
    }

    /**
     * Executed when a shard returns a query result.
     *
     * @param shardIndex The index of the shard in the list provided by {@link SearchProgressListener#onListShards} )}.
     */
    public void onQueryResult(int shardIndex) {
        logger.warn("XXX SSS CCSProgListener onQueryResult shardIdx: {}", shardIndex);
    }

    /**
     * Executed when a shard reports a query failure.
     *
     * @param shardIndex The index of the shard in the list provided by {@link SearchProgressListener#onListShards})}.
     * @param shardTarget The last shard target that thrown an exception.
     * @param exc The cause of the failure.
     */
    public void onQueryFailure(int shardIndex, SearchShardTarget shardTarget, Exception exc) {
        logger.warn("XXX SSS CCSProgListener onQueryFailure shardTarget: {}; exc: {}", shardTarget, exc);
    }

    /**
     * Executed when a partial reduce is created. The number of partial reduce can be controlled via
     * {@link SearchRequest#setBatchedReduceSize(int)}.
     *
     * @param shards The list of shards that are part of this reduce.
     * @param totalHits The total number of hits in this reduce.
     * @param aggs The partial result for aggregations.
     * @param reducePhase The version number for this reduce.
     */
    public void onPartialReduce(List<SearchShard> shards, TotalHits totalHits, InternalAggregations aggs, int reducePhase) {}

    /**
     * Executed once when the final reduce is created.
     *
     * @param shards The list of shards that are part of this reduce.
     * @param totalHits The total number of hits in this reduce.
     * @param aggs The final result for aggregations.
     * @param reducePhase The version number for this reduce.
     */
    public void onFinalReduce(List<SearchShard> shards, TotalHits totalHits, InternalAggregations aggs, int reducePhase) {}

    /**
     * Executed when a shard returns a fetch result.
     *
     * @param shardIndex The index of the shard in the list provided by {@link SearchProgressListener#onListShards})}.
     */
    public void onFetchResult(int shardIndex) {}

    /**
     * Executed when a shard reports a fetch failure.
     *
     * @param shardIndex The index of the shard in the list provided by {@link SearchProgressListener#onListShards})}.
     * @param shardTarget The last shard target that thrown an exception.
     * @param exc The cause of the failure.
     */
    public void onFetchFailure(int shardIndex, SearchShardTarget shardTarget, Exception exc) {
        logger.warn("XXX SSS CCSProgListener onFetchFailure shardTarget: {}; exc: {}", shardTarget, exc);
    }
}
