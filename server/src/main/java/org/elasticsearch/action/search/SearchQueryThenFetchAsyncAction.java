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
import org.apache.lucene.search.TopFieldDocs;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.routing.GroupShardsIterator;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.search.SearchPhaseResult;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.internal.AliasFilter;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.internal.ShardSearchRequest;
import org.elasticsearch.search.query.QuerySearchResult;
import org.elasticsearch.transport.RemoteClusterAware;
import org.elasticsearch.transport.Transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.elasticsearch.action.search.SearchPhaseController.getTopDocsSize;

class SearchQueryThenFetchAsyncAction extends AbstractSearchAsyncAction<SearchPhaseResult> {
    private static final Logger logger = LogManager.getLogger(SearchQueryThenFetchAsyncAction.class);
    private final SearchProgressListener progressListener;

    // informations to track the best bottom top doc globally.
    private final int topDocsSize;
    private final int trackTotalHitsUpTo;
    private volatile BottomSortValuesCollector bottomSortCollector;

    SearchQueryThenFetchAsyncAction(
        final Logger logger,
        final SearchTransportService searchTransportService,
        final BiFunction<String, String, Transport.Connection> nodeIdToConnection,
        final Map<String, AliasFilter> aliasFilter,
        final Map<String, Float> concreteIndexBoosts,
        final Executor executor,
        final QueryPhaseResultConsumer resultConsumer,
        final SearchRequest request,
        final ActionListener<SearchResponse> listener,
        final GroupShardsIterator<SearchShardIterator> shardsIts,
        final TransportSearchAction.SearchTimeProvider timeProvider,
        ClusterState clusterState,
        SearchTask task,
        SearchResponse.Clusters clusters
    ) {
        super(
            "query",
            logger,
            searchTransportService,
            nodeIdToConnection,
            aliasFilter,
            concreteIndexBoosts,
            executor,
            request,
            listener,
            shardsIts,
            timeProvider,
            clusterState,
            task,
            resultConsumer,
            request.getMaxConcurrentShardRequests(),
            clusters
        );
        this.topDocsSize = getTopDocsSize(request);
        this.trackTotalHitsUpTo = request.resolveTrackTotalHitsUpTo();
        this.progressListener = task.getProgressListener();

        logger.warn("XXX SQTFAA SearchQueryThenFetchAsyncAction ctor: shardsIts");
        for (SearchShardIterator shardsIt : shardsIts) {
            logger.warn(
                "   XXX SQTFAA  SQThenFAAction ctor: shardsIt: prefiltered: {}; skip: {}; shardId: {}; clusterAlias: {}",
                shardsIt.prefiltered(),
                shardsIt.skip(),
                shardsIt.shardId(),
                shardsIt.getClusterAlias()
            );
        }

        // register the release of the query consumer to free up the circuit breaker memory
        // at the end of the search
        addReleasable(resultConsumer);

        /// MP TODO: we need to create a non-NOOP SearchProgressListener for CCS searches for sync (async already has one)
        if (progressListener != SearchProgressListener.NOOP) {
            /// MP TODO ** for AsyncSearchTask and MutableSearchResponse, this sets the shared Clusters object, so that works here too
            notifyListShards(progressListener, clusters, request.source());
        }
    }

    protected void executePhaseOnShard(
        final SearchShardIterator shardIt,
        final SearchShardTarget shard,
        final SearchActionListener<SearchPhaseResult> listener
    ) {
        ShardSearchRequest request = rewriteShardSearchRequest(super.buildShardSearchRequest(shardIt, listener.requestIndex));
        getSearchTransport().sendExecuteQuery(getConnection(shard.getClusterAlias(), shard.getNodeId()), request, getTask(), listener);
    }

    @Override
    protected void onShardGroupFailure(int shardIndex, SearchShardTarget shardTarget, Exception exc) {
        /// MP --- START
        /*
         XXX SQTFAA onShardGroupFailure: idx: 0; clusterAlias: remote1,
             exc: [Michaels-MacBook-Pro.local][127.0.0.1:9301][indices:data/read/search[phase/query]] disconnected
         */
        /// MP TODO ** Hmm, this idx seems to be global across all clusters, NOT the index on the remote cluster
        /*
        XXX SQTFAA onShardGroupFailure: idx: 0; clusterAlias: remote1, exc: ... search[phase/query]] disconnected
        XXX SQTFAA onShardGroupFailure: idx: 3; clusterAlias: remote1, exc: ... search[phase/query]] disconnected
        XXX SQTFAA onShardGroupFailure: idx: 6; clusterAlias: remote1, exc: ... search[phase/query]] disconnected
         */
        if (clusters.hasClusterObjects() && clusters.isCcsMinimizeRoundtrips() == false) {
            String clusterAlias = shardTarget.getClusterAlias();
            logger.warn(
                "XXX SQTFAA onShardGroupFailure: idx: {}; clusterAlias: {}, shardId: {} exc: {}",
                shardIndex,
                clusterAlias,
                shardTarget.getShardId(),
                exc.getMessage()
            );
            if (clusterAlias == null) {
                clusterAlias = RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY;
            }
            AtomicReference<SearchResponse.Cluster> clusterRef = clusters.getCluster(clusterAlias);
            boolean swapped;
            do {
                TimeValue took = null;
                SearchResponse.Cluster curr = clusterRef.get();
                SearchResponse.Cluster.Status status = SearchResponse.Cluster.Status.RUNNING;
                int numFailedShards = curr.getFailedShards() == null ? 1 : curr.getFailedShards() + 1;

                /// MP TODO: should this be changed to assert curr.getTotalShards == null ?? should always be set now, right?
                if (curr.getTotalShards() != null) {
                    if (curr.getTotalShards() == numFailedShards) {
                        if (curr.isSkipUnavailable()) {
                            logger.warn("XXX SQTFAA onShardGroupFailure SETTING SKIPPED status bcs total=failed_shards !");
                            status = SearchResponse.Cluster.Status.SKIPPED;
                        } else {
                            logger.warn("XXX SQTFAA onShardGroupFailure SETTING FAILED status bcs total=failed_shards !");
                            status = SearchResponse.Cluster.Status.FAILED;
                        }
                    } else if (curr.getTotalShards() == numFailedShards + curr.getSuccessfulShards()) {
                        status = SearchResponse.Cluster.Status.PARTIAL;
                        took = new TimeValue(buildTookInMillis());
                    }
                }

                List<ShardSearchFailure> failures = new ArrayList<ShardSearchFailure>();
                curr.getFailures().forEach(failures::add);
                failures.add(new ShardSearchFailure(exc, shardTarget));
                SearchResponse.Cluster updated = new SearchResponse.Cluster(
                    curr.getClusterAlias(),
                    curr.getIndexExpression(),
                    curr.isSkipUnavailable(),
                    status,
                    curr.getTotalShards(),
                    curr.getSuccessfulShards(),
                    curr.getSkippedShards(),
                    numFailedShards,
                    failures,
                    took,
                    false
                );
                swapped = clusterRef.compareAndSet(curr, updated);
                logger.warn("XXX SQTFAA onShardGroupFailure swapped: {} ;;;; new cluster: {}", swapped, updated);
            } while (swapped == false);
        }
        /// MP --- END

        progressListener.notifyQueryFailure(shardIndex, shardTarget, exc);
    }

    @Override
    protected void onShardResult(SearchPhaseResult result, SearchShardIterator shardIt) {
        /// MP --- START
        /// MP TODO after the SearchShardsAction (and ClusterSearchShard) we should know how many shards there are per index, per cluster,
        /// MP TODO right? If yes, we need to record that in Cluster object so it knows when it is done and can do that accounting
        /// MP TODO for success vs. partial, etc.
        /*
         XXX SQTFAA onShardResult: QueryResult size: 3; clusterAlias: null, shardId: [blogs][1]
         XXX SQTFAA onShardResult: QueryResult size: 3; clusterAlias: remote1, shardId: [blogs][0]
         */
        logger.warn(
            "XXX SQTFAA onShardResult: QueryResult size: {}; totalHits.value: {}, clusterAlias: {}, shardId: {}",
            result.queryResult().size(),
            result.queryResult().getTotalHits().value,
            shardIt.getClusterAlias(),
            shardIt.shardId()
        );
        /// MP --- END
        QuerySearchResult queryResult = result.queryResult();
        if (queryResult.isNull() == false
            // disable sort optims for scroll requests because they keep track of the last bottom doc locally (per shard)
            && getRequest().scroll() == null
            // top docs are already consumed if the query was cancelled or in error.
            && queryResult.hasConsumedTopDocs() == false
            && queryResult.topDocs() != null
            && queryResult.topDocs().topDocs.getClass() == TopFieldDocs.class) {
            TopFieldDocs topDocs = (TopFieldDocs) queryResult.topDocs().topDocs;
            if (bottomSortCollector == null) {
                synchronized (this) {
                    if (bottomSortCollector == null) {
                        bottomSortCollector = new BottomSortValuesCollector(topDocsSize, topDocs.fields);
                    }
                }
            }
            bottomSortCollector.consumeTopDocs(topDocs, queryResult.sortValueFormats());
        }

        /// MP --- START
        /// MP: TODO: somehow we need to inspect the SearchPhaseResult and determine whether the query was successful?
        /// MP: TODO: I guess it would be since if it failed onShardGroupFailure would be called?
        /// MP: TODO: should we be marking this as succesful here? or not until the fetch phase has finished?
        // SearchResponse.Cluster cluster = clusters.getCluster(shardIt.getClusterAlias());
        // if (cluster != null) {
        // cluster.markShardSuccessful(shardIt.shardId());
        // }
        /// MP --- END

        super.onShardResult(result, shardIt);
    }

    @Override
    protected SearchPhase getNextPhase(final SearchPhaseResults<SearchPhaseResult> results, SearchPhaseContext context) {
        return new FetchSearchPhase(results, null, this);
    }

    private ShardSearchRequest rewriteShardSearchRequest(ShardSearchRequest request) {
        if (bottomSortCollector == null) {
            return request;
        }

        // disable tracking total hits if we already reached the required estimation.
        if (trackTotalHitsUpTo != SearchContext.TRACK_TOTAL_HITS_ACCURATE && bottomSortCollector.getTotalHits() > trackTotalHitsUpTo) {
            request.source(request.source().shallowCopy().trackTotalHits(false));
        }

        // set the current best bottom field doc
        if (bottomSortCollector.getBottomSortValues() != null) {
            request.setBottomSortValues(bottomSortCollector.getBottomSortValues());
        }
        return request;
    }
}
