/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.ccs;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.IndexModule;
import org.elasticsearch.index.query.MatchAllQueryBuilder;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.index.shard.SearchOperationListener;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.SearchPlugin;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.internal.LegacyReaderContext;
import org.elasticsearch.search.internal.ReaderContext;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.query.ThrowingQueryBuilder22;
import org.elasticsearch.test.AbstractMultiClustersTestCase;
import org.elasticsearch.test.InternalTestCluster;
import org.elasticsearch.transport.RemoteClusterAware;
import org.elasticsearch.transport.RemoteTransportException;
import org.junit.Before;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;

public class CrossClusterSearchIT extends AbstractMultiClustersTestCase {

    private static final String REMOTE_CLUSTER = "cluster_a";
    private static long EARLIEST_TIMESTAMP = 1691348810000L;
    private static long LATEST_TIMESTAMP = 1691348820000L;

    @Override
    protected Collection<String> remoteClusterAlias() {
        return List.of(REMOTE_CLUSTER);
    }

    @Override
    protected boolean reuseClusters() {
        return false;
    }

    public void testClusterDetailsAfterSuccessfulCCS() throws Exception {
        Map<String, Object> testClusterInfo = setupTwoClusters();
        String localIndex = (String) testClusterInfo.get("local.index");
        String remoteIndex = (String) testClusterInfo.get("remote.index");
        int localNumShards = (Integer) testClusterInfo.get("local.num_shards");
        int remoteNumShards = (Integer) testClusterInfo.get("remote.num_shards");

        PlainActionFuture<SearchResponse> queryFuture = new PlainActionFuture<>();
        SearchRequest searchRequest = new SearchRequest(localIndex, REMOTE_CLUSTER + ":" + remoteIndex);
        searchRequest.allowPartialSearchResults(false);
        boolean minimizeRoundtrips = randomBoolean();
        searchRequest.setCcsMinimizeRoundtrips(minimizeRoundtrips);
        boolean dfsQueryThenFetch = randomBoolean();
        if (dfsQueryThenFetch) {
            searchRequest.searchType(SearchType.DFS_QUERY_THEN_FETCH);
        }
        searchRequest.source(new SearchSourceBuilder().query(new MatchAllQueryBuilder()).size(1000));
        client(LOCAL_CLUSTER).search(searchRequest, queryFuture);

        assertBusy(() -> assertTrue(queryFuture.isDone()));

        SearchResponse searchResponse = queryFuture.get();
        assertNotNull(searchResponse);
        System.err.println(searchResponse);

        SearchResponse.Clusters clusters = searchResponse.getClusters();
        System.err.println(clusters);
        System.err.println(clusters.getCluster(""));
        System.err.println(clusters.getCluster(REMOTE_CLUSTER));
        assertFalse("search cluster results should NOT be marked as partial", clusters.hasPartialResults());
        assertThat(clusters.getTotal(), equalTo(2));
        assertThat(clusters.getSuccessful(), equalTo(2));
        assertThat(clusters.getSkipped(), equalTo(0));

        SearchResponse.Cluster localClusterSearchInfo = clusters.getCluster(RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY).get();
        assertNotNull(localClusterSearchInfo);
        assertThat(localClusterSearchInfo.getStatus(), equalTo(SearchResponse.Cluster.Status.SUCCESSFUL));
        assertThat(localClusterSearchInfo.getIndexExpression(), equalTo(localIndex));
        assertThat(localClusterSearchInfo.getTotalShards(), equalTo(localNumShards));
        assertThat(localClusterSearchInfo.getSuccessfulShards(), equalTo(localNumShards));
        assertThat(localClusterSearchInfo.getSkippedShards(), equalTo(0));
        assertThat(localClusterSearchInfo.getFailedShards(), equalTo(0));
        assertThat(localClusterSearchInfo.getFailures().size(), equalTo(0));
        assertThat(localClusterSearchInfo.getTook().millis(), greaterThan(0L));

        SearchResponse.Cluster remoteClusterSearchInfo = clusters.getCluster(REMOTE_CLUSTER).get();
        assertNotNull(remoteClusterSearchInfo);
        assertThat(remoteClusterSearchInfo.getStatus(), equalTo(SearchResponse.Cluster.Status.SUCCESSFUL));
        assertThat(remoteClusterSearchInfo.getIndexExpression(), equalTo(remoteIndex));
        assertThat(remoteClusterSearchInfo.getTotalShards(), equalTo(remoteNumShards));
        assertThat(remoteClusterSearchInfo.getSuccessfulShards(), equalTo(remoteNumShards));
        assertThat(remoteClusterSearchInfo.getSkippedShards(), equalTo(0));
        assertThat(remoteClusterSearchInfo.getFailedShards(), equalTo(0));
        assertThat(remoteClusterSearchInfo.getFailures().size(), equalTo(0));
        assertThat(remoteClusterSearchInfo.getTook().millis(), greaterThan(0L));
    }

    // CCS with a search where the timestamp of the query cannot match so should be SUCCESSFUL with all shards skipped
    // during can-match
    public void testCCSClusterDetailsWhereAllShardsSkippedInCanMatch() throws Exception {
        Map<String, Object> testClusterInfo = setupTwoClusters();
        String localIndex = (String) testClusterInfo.get("local.index");
        String remoteIndex = (String) testClusterInfo.get("remote.index");
        int localNumShards = (Integer) testClusterInfo.get("local.num_shards");
        int remoteNumShards = (Integer) testClusterInfo.get("remote.num_shards");

        PlainActionFuture<SearchResponse> queryFuture = new PlainActionFuture<>();
        SearchRequest searchRequest = new SearchRequest(localIndex, REMOTE_CLUSTER + ":" + remoteIndex);
        searchRequest.allowPartialSearchResults(false);
        boolean minimizeRoundtrips = randomBoolean();
        System.err.println("minimizeRoundtrips: " + minimizeRoundtrips);
        searchRequest.setCcsMinimizeRoundtrips(minimizeRoundtrips);
        boolean dfsQueryThenFetch = randomBoolean();
        System.err.println("dfsQueryThenFetch: " + dfsQueryThenFetch);
        if (dfsQueryThenFetch) {
            searchRequest.searchType(SearchType.DFS_QUERY_THEN_FETCH);
        }
        RangeQueryBuilder rangeQueryBuilder = new RangeQueryBuilder("@timestamp").from(EARLIEST_TIMESTAMP - 2000)
            .to(EARLIEST_TIMESTAMP - 1000);

        searchRequest.source(new SearchSourceBuilder().query(rangeQueryBuilder).size(1000));
        client(LOCAL_CLUSTER).search(searchRequest, queryFuture);

        assertBusy(() -> assertTrue(queryFuture.isDone()));

        SearchResponse searchResponse = queryFuture.get();
        assertNotNull(searchResponse);
        System.err.println(searchResponse);

        SearchResponse.Clusters clusters = searchResponse.getClusters();
        System.err.println(clusters);
        System.err.println(clusters.getCluster(""));
        System.err.println(clusters.getCluster(REMOTE_CLUSTER));
        assertFalse("search cluster results should NOT be marked as partial", clusters.hasPartialResults());
        assertThat(clusters.getTotal(), equalTo(2));
        assertThat(clusters.getSuccessful(), equalTo(2));
        assertThat(clusters.getSkipped(), equalTo(0));

        SearchResponse.Cluster localClusterSearchInfo = clusters.getCluster(RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY).get();
        assertNotNull(localClusterSearchInfo);
        SearchResponse.Cluster remoteClusterSearchInfo = clusters.getCluster(REMOTE_CLUSTER).get();
        assertNotNull(remoteClusterSearchInfo);

        System.err.println("local :" + localClusterSearchInfo);
        System.err.println("remote:" + remoteClusterSearchInfo);

        assertThat(localClusterSearchInfo.getStatus(), equalTo(SearchResponse.Cluster.Status.SUCCESSFUL));
        assertThat(localClusterSearchInfo.getTotalShards(), equalTo(localNumShards));
        assertThat(localClusterSearchInfo.getSuccessfulShards(), equalTo(localNumShards));
        // artifact of the way can-match skipping is done during local search vs. SearchShards API
        // used during ccs_minimize_roundtrips=false
        int expectedSkipped = localNumShards - 1;   /// MP TODO: Hmm, is this always true?
        // default preFilterShardSize for sync search will not do can-match on this test setup
        assertThat(localClusterSearchInfo.getSkippedShards(), equalTo(0));
        assertThat(localClusterSearchInfo.getFailedShards(), equalTo(0));
        assertThat(localClusterSearchInfo.getFailures().size(), equalTo(0));
        assertThat(localClusterSearchInfo.getTook().millis(), greaterThanOrEqualTo(0L));

        assertThat(remoteClusterSearchInfo.getStatus(), equalTo(SearchResponse.Cluster.Status.SUCCESSFUL));
        assertThat(remoteClusterSearchInfo.getTotalShards(), equalTo(remoteNumShards));
        assertThat(remoteClusterSearchInfo.getSuccessfulShards(), equalTo(remoteNumShards));
        System.err.println(remoteClusterSearchInfo);
        if (clusters.isCcsMinimizeRoundtrips()) {
            expectedSkipped = 0;
        } else {
            expectedSkipped = clusters.isCcsMinimizeRoundtrips() ? remoteNumShards - 1 : remoteNumShards;
        }
        assertThat(remoteClusterSearchInfo.getSkippedShards(), equalTo(expectedSkipped)); // all should be skipped
        assertThat(remoteClusterSearchInfo.getFailedShards(), equalTo(0));
        assertThat(remoteClusterSearchInfo.getFailures().size(), equalTo(0));
        assertThat(remoteClusterSearchInfo.getTook().millis(), greaterThanOrEqualTo(0L));
    }

    public void testClusterDetailsAfterCCSWithFailuresOnOneShardOnly() throws Exception {
        Map<String, Object> testClusterInfo = setupTwoClusters();
        String localIndex = (String) testClusterInfo.get("local.index");
        String remoteIndex = (String) testClusterInfo.get("remote.index");
        int localNumShards = (Integer) testClusterInfo.get("local.num_shards");
        int remoteNumShards = (Integer) testClusterInfo.get("remote.num_shards");

        PlainActionFuture<SearchResponse> queryFuture = new PlainActionFuture<>();
        SearchRequest searchRequest = new SearchRequest(localIndex, REMOTE_CLUSTER + ":" + remoteIndex);
        searchRequest.allowPartialSearchResults(true);
        boolean minimizeRoundtrips = randomBoolean();
        System.err.println("minimizeRoundtrips: " + minimizeRoundtrips);
        searchRequest.setCcsMinimizeRoundtrips(minimizeRoundtrips);
        boolean dfsQueryThenFetch = randomBoolean();
        System.err.println("dfsQueryThenFetch: " + dfsQueryThenFetch);
        if (dfsQueryThenFetch) {
            searchRequest.searchType(SearchType.DFS_QUERY_THEN_FETCH);
        }
        // shardId 0 means to throw the Exception only on shard 0; all others should work
        ThrowingQueryBuilder22 queryBuilder = new ThrowingQueryBuilder22(randomLong(), new IllegalStateException("index corrupted"), 0);
        searchRequest.source(new SearchSourceBuilder().query(queryBuilder).size(10));
        client(LOCAL_CLUSTER).search(searchRequest, queryFuture);

        assertBusy(() -> assertTrue(queryFuture.isDone()));

        SearchResponse searchResponse = queryFuture.get();
        assertNotNull(searchResponse);
        System.err.println(searchResponse);

        SearchResponse.Clusters clusters = searchResponse.getClusters();
        System.err.println(clusters);
        System.err.println(clusters.getCluster(""));
        System.err.println(clusters.getCluster(REMOTE_CLUSTER));
        assertThat(clusters.getTotal(), equalTo(2));
        assertThat(clusters.getSuccessful(), equalTo(2));
        assertThat(clusters.getSkipped(), equalTo(0));

        SearchResponse.Cluster localClusterSearchInfo = clusters.getCluster(RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY).get();
        assertNotNull(localClusterSearchInfo);
        assertThat(localClusterSearchInfo.getStatus(), equalTo(SearchResponse.Cluster.Status.PARTIAL));
        assertThat(localClusterSearchInfo.getTotalShards(), equalTo(localNumShards));
        assertThat(localClusterSearchInfo.getSuccessfulShards(), equalTo(localNumShards - 1));
        assertThat(localClusterSearchInfo.getSkippedShards(), equalTo(0));
        assertThat(localClusterSearchInfo.getFailedShards(), equalTo(1));
        assertThat(localClusterSearchInfo.getFailures().size(), equalTo(1));
        assertThat(localClusterSearchInfo.getTook().millis(), greaterThan(0L));
        ShardSearchFailure localShardSearchFailure = localClusterSearchInfo.getFailures().get(0);
        assertTrue("should have 'index corrupted' in reason", localShardSearchFailure.reason().contains("index corrupted"));

        SearchResponse.Cluster remoteClusterSearchInfo = clusters.getCluster(REMOTE_CLUSTER).get();
        assertNotNull(remoteClusterSearchInfo);
        assertThat(remoteClusterSearchInfo.getStatus(), equalTo(SearchResponse.Cluster.Status.PARTIAL));
        assertThat(remoteClusterSearchInfo.getTotalShards(), equalTo(remoteNumShards));
        assertThat(remoteClusterSearchInfo.getSuccessfulShards(), equalTo(remoteNumShards - 1));
        assertThat(remoteClusterSearchInfo.getSkippedShards(), equalTo(0));
        assertThat(remoteClusterSearchInfo.getFailedShards(), equalTo(1));
        assertThat(remoteClusterSearchInfo.getFailures().size(), equalTo(1));
        assertThat(remoteClusterSearchInfo.getTook().millis(), greaterThan(0L));
        ShardSearchFailure remoteShardSearchFailure = remoteClusterSearchInfo.getFailures().get(0);
        assertTrue("should have 'index corrupted' in reason", remoteShardSearchFailure.reason().contains("index corrupted"));
    }

    public void testClusterDetailsAfterCCSWithFailuresOnRemoteClusterOnly() throws Exception {
        Map<String, Object> testClusterInfo = setupTwoClusters();
        String localIndex = (String) testClusterInfo.get("local.index");
        String remoteIndex = (String) testClusterInfo.get("remote.index");
        int localNumShards = (Integer) testClusterInfo.get("local.num_shards");
        int remoteNumShards = (Integer) testClusterInfo.get("remote.num_shards");
        boolean skipUnavailable = (Boolean) testClusterInfo.get("remote.skip_unavailable");
        System.err.println("skipUnavailable: " + skipUnavailable);

        PlainActionFuture<SearchResponse> queryFuture = new PlainActionFuture<>();
        SearchRequest searchRequest = new SearchRequest(localIndex, REMOTE_CLUSTER + ":" + remoteIndex);
        searchRequest.allowPartialSearchResults(true);
        boolean minimizeRoundtrips = randomBoolean();
        System.err.println("minimizeRoundtrips: " + minimizeRoundtrips);
        searchRequest.setCcsMinimizeRoundtrips(minimizeRoundtrips);
        boolean dfsQueryThenFetch = randomBoolean();
        if (dfsQueryThenFetch) {
            searchRequest.searchType(SearchType.DFS_QUERY_THEN_FETCH);
        }
        System.err.println("DFS_QUERY_THEN_FETCH: " + dfsQueryThenFetch);
        // throw Exception on all shards of remoteIndex, but not against localIndex
        ThrowingQueryBuilder22 queryBuilder = new ThrowingQueryBuilder22(
            randomLong(),
            new IllegalStateException("index corrupted"),
            remoteIndex
        );
        searchRequest.source(new SearchSourceBuilder().query(queryBuilder).size(10));
        client(LOCAL_CLUSTER).search(searchRequest, queryFuture);

        assertBusy(() -> assertTrue(queryFuture.isDone()));

        /// MP TODO: problem if you set minimizeRoundtrips=true but do DFS_QUERY_THEN_FETCH, that negates it, but the
        /// MP TODO: Clusters object still thinks it is doing MRT=true? So that needs to be negated?

        // MRT=false only fails a search for a skip_unavailable=true cluster if it isn't available at the start of the query
        // for a case where the cluster is available but the search fails after the search starts, then only MRT=true
        // fails the search (and throws an Exception here).
        // We must also check the 'dfsQueryThenFetch' flag since if it is true, then the CCS will not minimize roundtrips
        if (skipUnavailable == false && minimizeRoundtrips && dfsQueryThenFetch == false) {
            ExecutionException ee = expectThrows(ExecutionException.class, () -> queryFuture.get());
            assertNotNull(ee.getCause());
            assertThat(ee.getCause(), instanceOf(RemoteTransportException.class));
            Throwable rootCause = ExceptionsHelper.unwrap(ee.getCause(), IllegalStateException.class);
            assertThat(rootCause.getMessage(), containsString("index corrupted"));
        } else {
            SearchResponse searchResponse = queryFuture.get();
            assertNotNull(searchResponse);
            System.err.println(searchResponse);

            SearchResponse.Clusters clusters = searchResponse.getClusters();
            if (dfsQueryThenFetch) {
                assertThat(clusters.isCcsMinimizeRoundtrips(), equalTo(false));
            } else {
                assertThat(clusters.isCcsMinimizeRoundtrips(), equalTo(minimizeRoundtrips));
            }
            assertThat(clusters.getTotal(), equalTo(2));
            if (skipUnavailable && clusters.isCcsMinimizeRoundtrips()) {
                assertThat(clusters.getSuccessful(), equalTo(1));
                assertThat(clusters.getSkipped(), equalTo(1));
            } else {
                assertThat(clusters.getSuccessful(), equalTo(1));
                assertThat(clusters.getSkipped(), equalTo(1));
            }

            SearchResponse.Cluster localClusterSearchInfo = clusters.getCluster(RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY).get();
            System.err.println(localClusterSearchInfo);
            assertNotNull(localClusterSearchInfo);
            assertThat(localClusterSearchInfo.getStatus(), equalTo(SearchResponse.Cluster.Status.SUCCESSFUL));
            assertThat(localClusterSearchInfo.getTotalShards(), equalTo(localNumShards));
            assertThat(localClusterSearchInfo.getSuccessfulShards(), equalTo(localNumShards));
            assertThat(localClusterSearchInfo.getSkippedShards(), equalTo(0));
            assertThat(localClusterSearchInfo.getFailedShards(), equalTo(0));
            assertThat(localClusterSearchInfo.getFailures().size(), equalTo(0));
            assertThat(localClusterSearchInfo.getTook().millis(), greaterThan(0L));

            SearchResponse.Cluster remoteClusterSearchInfo = clusters.getCluster(REMOTE_CLUSTER).get();
            System.err.println(remoteClusterSearchInfo);

            assertNotNull(remoteClusterSearchInfo);
            SearchResponse.Cluster.Status expectedStatus = skipUnavailable
                ? SearchResponse.Cluster.Status.SKIPPED
                : SearchResponse.Cluster.Status.FAILED;
            assertThat(remoteClusterSearchInfo.getStatus(), equalTo(expectedStatus));
            if (clusters.isCcsMinimizeRoundtrips()) {
                assertNull(remoteClusterSearchInfo.getTotalShards());
                assertNull(remoteClusterSearchInfo.getSuccessfulShards());
                assertNull(remoteClusterSearchInfo.getSkippedShards());
                assertNull(remoteClusterSearchInfo.getFailedShards());
                assertThat(remoteClusterSearchInfo.getFailures().size(), equalTo(1));
            } else {
                assertThat(remoteClusterSearchInfo.getTotalShards(), equalTo(remoteNumShards));
                System.err.println(remoteClusterSearchInfo);
                assertThat(remoteClusterSearchInfo.getSuccessfulShards(), equalTo(0));
                assertThat(remoteClusterSearchInfo.getSkippedShards(), equalTo(0));
                assertThat(remoteClusterSearchInfo.getFailedShards(), equalTo(remoteNumShards));
                assertThat(remoteClusterSearchInfo.getFailures().size(), equalTo(remoteNumShards));
            }
            assertNull(remoteClusterSearchInfo.getTook());
            assertFalse(remoteClusterSearchInfo.isTimedOut());
            ShardSearchFailure remoteShardSearchFailure = remoteClusterSearchInfo.getFailures().get(0);
            assertTrue("should have 'index corrupted' in reason", remoteShardSearchFailure.reason().contains("index corrupted"));
        }
    }

    public void testRemoteClusterOnlyCCSSuccessfulResult() throws Exception {
        Map<String, Object> testClusterInfo = setupTwoClusters();
        String remoteIndex = (String) testClusterInfo.get("remote.index");
        int remoteNumShards = (Integer) testClusterInfo.get("remote.num_shards");

        PlainActionFuture<SearchResponse> queryFuture = new PlainActionFuture<>();
        SearchRequest searchRequest = new SearchRequest(REMOTE_CLUSTER + ":" + remoteIndex);
        searchRequest.allowPartialSearchResults(false);
        boolean minimizeRoundtrips = randomBoolean();
        searchRequest.setCcsMinimizeRoundtrips(minimizeRoundtrips);
        boolean dfsQueryThenFetch = randomBoolean();
        if (dfsQueryThenFetch) {
            searchRequest.searchType(SearchType.DFS_QUERY_THEN_FETCH);
        }
        searchRequest.source(new SearchSourceBuilder().query(new MatchAllQueryBuilder()).size(1000));
        client(LOCAL_CLUSTER).search(searchRequest, queryFuture);

        assertBusy(() -> assertTrue(queryFuture.isDone()));

        SearchResponse searchResponse = queryFuture.get();
        assertNotNull(searchResponse);
        System.err.println(searchResponse);

        SearchResponse.Clusters clusters = searchResponse.getClusters();
        assertTrue(clusters.hasRemoteClusters());
        assertFalse("search cluster results should NOT be marked as partial", clusters.hasPartialResults());
        assertThat(clusters.getTotal(), equalTo(1));
        assertThat(clusters.getSuccessful(), equalTo(1));
        assertThat(clusters.getSkipped(), equalTo(0));

        assertNull(clusters.getCluster(RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY));

        SearchResponse.Cluster remoteClusterSearchInfo = clusters.getCluster(REMOTE_CLUSTER).get();
        assertNotNull(remoteClusterSearchInfo);
        assertThat(remoteClusterSearchInfo.getStatus(), equalTo(SearchResponse.Cluster.Status.SUCCESSFUL));
        assertThat(remoteClusterSearchInfo.getTotalShards(), equalTo(remoteNumShards));
        assertThat(remoteClusterSearchInfo.getSuccessfulShards(), equalTo(remoteNumShards));
        assertThat(remoteClusterSearchInfo.getSkippedShards(), equalTo(0));
        assertThat(remoteClusterSearchInfo.getFailedShards(), equalTo(0));
        assertThat(remoteClusterSearchInfo.getFailures().size(), equalTo(0));
        assertThat(remoteClusterSearchInfo.getTook().millis(), greaterThan(0L));
    }

    public void testRemoteClusterOnlyCCSWithFailuresOnOneShardOnly() throws Exception {
        Map<String, Object> testClusterInfo = setupTwoClusters();
        String remoteIndex = (String) testClusterInfo.get("remote.index");
        int remoteNumShards = (Integer) testClusterInfo.get("remote.num_shards");

        PlainActionFuture<SearchResponse> queryFuture = new PlainActionFuture<>();
        SearchRequest searchRequest = new SearchRequest(REMOTE_CLUSTER + ":" + remoteIndex);
        searchRequest.allowPartialSearchResults(true);
        boolean minimizeRoundtrips = randomBoolean();
        searchRequest.setCcsMinimizeRoundtrips(minimizeRoundtrips);
        boolean dfsQueryThenFetch = randomBoolean();
        if (dfsQueryThenFetch) {
            searchRequest.searchType(SearchType.DFS_QUERY_THEN_FETCH);
        }
        // shardId 0 means to throw the Exception only on shard 0; all others should work
        ThrowingQueryBuilder22 queryBuilder = new ThrowingQueryBuilder22(randomLong(), new IllegalStateException("index corrupted"), 0);
        searchRequest.source(new SearchSourceBuilder().query(queryBuilder).size(10));
        client(LOCAL_CLUSTER).search(searchRequest, queryFuture);

        assertBusy(() -> assertTrue(queryFuture.isDone()));

        SearchResponse searchResponse = queryFuture.get();
        assertNotNull(searchResponse);
        System.err.println(searchResponse);

        SearchResponse.Clusters clusters = searchResponse.getClusters();
        assertThat(clusters.getTotal(), equalTo(1));
        assertThat(clusters.getSuccessful(), equalTo(1));
        assertThat(clusters.getSkipped(), equalTo(0));

        assertNull(clusters.getCluster(RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY));

        SearchResponse.Cluster remoteClusterSearchInfo = clusters.getCluster(REMOTE_CLUSTER).get();
        assertNotNull(remoteClusterSearchInfo);
        assertThat(remoteClusterSearchInfo.getStatus(), equalTo(SearchResponse.Cluster.Status.PARTIAL));
        assertThat(remoteClusterSearchInfo.getTotalShards(), equalTo(remoteNumShards));
        assertThat(remoteClusterSearchInfo.getSuccessfulShards(), equalTo(remoteNumShards - 1));
        assertThat(remoteClusterSearchInfo.getSkippedShards(), equalTo(0));
        assertThat(remoteClusterSearchInfo.getFailedShards(), equalTo(1));
        assertThat(remoteClusterSearchInfo.getFailures().size(), equalTo(1));
        assertThat(remoteClusterSearchInfo.getTook().millis(), greaterThan(0L));
        ShardSearchFailure remoteShardSearchFailure = remoteClusterSearchInfo.getFailures().get(0);
        assertTrue("should have 'index corrupted' in reason", remoteShardSearchFailure.reason().contains("index corrupted"));
    }

    public void testRemoteClusterOnlyCCSWithFailuresOnAllShards() throws Exception {
        Map<String, Object> testClusterInfo = setupTwoClusters();
        String remoteIndex = (String) testClusterInfo.get("remote.index");
        int remoteNumShards = (Integer) testClusterInfo.get("remote.num_shards");
        boolean skipUnavailable = (Boolean) testClusterInfo.get("remote.skip_unavailable");
        System.err.println("skipUnavailable: " + skipUnavailable);

        PlainActionFuture<SearchResponse> queryFuture = new PlainActionFuture<>();
        SearchRequest searchRequest = new SearchRequest(REMOTE_CLUSTER + ":" + remoteIndex);
        searchRequest.allowPartialSearchResults(true);
        boolean minimizeRoundtrips = randomBoolean();
        System.err.println("minimizeRoundtrips: " + minimizeRoundtrips);
        searchRequest.setCcsMinimizeRoundtrips(minimizeRoundtrips);
        boolean dfsQueryThenFetch = randomBoolean();
        if (dfsQueryThenFetch) {
            searchRequest.searchType(SearchType.DFS_QUERY_THEN_FETCH);
        }
        // shardId -1 means to throw the Exception on all shards, so should result in complete search failure
        ThrowingQueryBuilder22 queryBuilder = new ThrowingQueryBuilder22(randomLong(), new IllegalStateException("index corrupted"), -1);
        searchRequest.source(new SearchSourceBuilder().query(queryBuilder).size(10));
        client(LOCAL_CLUSTER).search(searchRequest, queryFuture);

        assertBusy(() -> assertTrue(queryFuture.isDone()));

        if (skipUnavailable == false) {
            ExecutionException ee = expectThrows(ExecutionException.class, () -> queryFuture.get());
            assertNotNull(ee.getCause());
            Throwable rootCause = ExceptionsHelper.unwrap(ee, IllegalStateException.class);
            assertThat(rootCause.getMessage(), containsString("index corrupted"));
        } else {
            SearchResponse searchResponse = queryFuture.get();
            assertNotNull(searchResponse);
            System.err.println(searchResponse);
            SearchResponse.Clusters clusters = searchResponse.getClusters();
            assertThat(clusters.getTotal(), equalTo(1));
            assertThat(clusters.getSuccessful(), equalTo(0));
            assertThat(clusters.getSkipped(), equalTo(1));

            assertNull(clusters.getCluster(RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY));

            SearchResponse.Cluster remoteClusterSearchInfo = clusters.getCluster(REMOTE_CLUSTER).get();
            assertNotNull(remoteClusterSearchInfo);
            SearchResponse.Cluster.Status expectedStatus = skipUnavailable
                ? SearchResponse.Cluster.Status.SKIPPED
                : SearchResponse.Cluster.Status.FAILED;
            assertThat(remoteClusterSearchInfo.getStatus(), equalTo(expectedStatus));
            if (clusters.isCcsMinimizeRoundtrips()) {
                assertNull(remoteClusterSearchInfo.getTotalShards());
                assertNull(remoteClusterSearchInfo.getSuccessfulShards());
                assertNull(remoteClusterSearchInfo.getSkippedShards());
                assertNull(remoteClusterSearchInfo.getFailedShards());
                assertThat(remoteClusterSearchInfo.getFailures().size(), equalTo(1));
            } else {
                assertThat(remoteClusterSearchInfo.getTotalShards(), equalTo(remoteNumShards));
                assertThat(remoteClusterSearchInfo.getSuccessfulShards(), equalTo(0));
                assertThat(remoteClusterSearchInfo.getSkippedShards(), equalTo(0));
                assertThat(remoteClusterSearchInfo.getFailedShards(), equalTo(remoteNumShards));
                assertThat(remoteClusterSearchInfo.getFailures().size(), equalTo(remoteNumShards));
            }
            assertNull(remoteClusterSearchInfo.getTook());
            assertFalse(remoteClusterSearchInfo.isTimedOut());
            ShardSearchFailure remoteShardSearchFailure = remoteClusterSearchInfo.getFailures().get(0);
            assertTrue("should have 'index corrupted' in reason", remoteShardSearchFailure.reason().contains("index corrupted"));
        }
    }

    private Map<String, Object> setupTwoClusters() {
        String localIndex = "demo";
        int numShardsLocal = randomIntBetween(3, 6);
        Settings localSettings = indexSettings(numShardsLocal, 0).build();
        assertAcked(
            client(LOCAL_CLUSTER).admin()
                .indices()
                .prepareCreate(localIndex)
                .setSettings(localSettings)
                .setMapping("@timestamp", "type=date", "f", "type=text")
        );
        indexDocs(client(LOCAL_CLUSTER), localIndex);

        String remoteIndex = "prod";
        int numShardsRemote = randomIntBetween(3, 6);
        final InternalTestCluster remoteCluster = cluster(REMOTE_CLUSTER);
        remoteCluster.ensureAtLeastNumDataNodes(randomIntBetween(1, 3));
        final Settings.Builder remoteSettings = Settings.builder();
        remoteSettings.put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, numShardsRemote);

        assertAcked(
            client(REMOTE_CLUSTER).admin()
                .indices()
                .prepareCreate(remoteIndex)
                .setSettings(Settings.builder().put(remoteSettings.build()).put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0))
                .setMapping("@timestamp", "type=date", "f", "type=text")
        );
        assertFalse(
            client(REMOTE_CLUSTER).admin()
                .cluster()
                .prepareHealth(remoteIndex)
                .setWaitForYellowStatus()
                .setTimeout(TimeValue.timeValueSeconds(10))
                .get()
                .isTimedOut()
        );
        indexDocs(client(REMOTE_CLUSTER), remoteIndex);

        String skipUnavailableKey = Strings.format("cluster.remote.%s.skip_unavailable", REMOTE_CLUSTER);
        Setting<?> skipUnavailableSetting = cluster(REMOTE_CLUSTER).clusterService().getClusterSettings().get(skipUnavailableKey);
        boolean skipUnavailable = (boolean) cluster(RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY).clusterService()
            .getClusterSettings()
            .get(skipUnavailableSetting);

        Map<String, Object> clusterInfo = new HashMap<>();
        clusterInfo.put("local.num_shards", numShardsLocal);
        clusterInfo.put("local.index", localIndex);
        clusterInfo.put("remote.num_shards", numShardsRemote);
        clusterInfo.put("remote.index", remoteIndex);
        clusterInfo.put("remote.skip_unavailable", skipUnavailable);
        return clusterInfo;
    }

    private int indexDocs(Client client, String index) {
        int numDocs = between(50, 100);
        for (int i = 0; i < numDocs; i++) {
            long ts = EARLIEST_TIMESTAMP + i;
            if (i == numDocs - 1) {
                ts = LATEST_TIMESTAMP;
            }
            client.prepareIndex(index).setSource("f", "v", "@timestamp", ts).get();
        }
        client.admin().indices().prepareRefresh(index).get();
        return numDocs;
    }

    /// MP --- END

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins(String clusterAlias) {
        List<Class<? extends Plugin>> plugs = Arrays.asList(SearchListenerPlugin.class, TestQueryBuilderPlugin.class);
        return Stream.concat(super.nodePlugins(clusterAlias).stream(), plugs.stream()).collect(Collectors.toList());
    }

    public static class TestQueryBuilderPlugin extends Plugin implements SearchPlugin {
        public TestQueryBuilderPlugin() {}

        @Override
        public List<QuerySpec<?>> getQueries() {
            QuerySpec<ThrowingQueryBuilder22> throwingSpec = new QuerySpec<>(
                ThrowingQueryBuilder22.NAME,
                ThrowingQueryBuilder22::new,
                p -> {
                    throw new IllegalStateException("not implemented");
                }
            );

            return List.of(throwingSpec);
        }
    }

    @Before
    public void resetSearchListenerPlugin() throws Exception {
        SearchListenerPlugin.reset();
    }

    /// MP TODO - do we need this in this revised test?
    public static class SearchListenerPlugin extends Plugin {
        private static final AtomicReference<CountDownLatch> startedLatch = new AtomicReference<>();
        private static final AtomicReference<CountDownLatch> queryLatch = new AtomicReference<>();

        static void reset() {
            startedLatch.set(new CountDownLatch(1));
        }

        static void blockQueryPhase() {
            queryLatch.set(new CountDownLatch(1));
        }

        static void allowQueryPhase() {
            final CountDownLatch latch = queryLatch.get();
            if (latch != null) {
                latch.countDown();
            }
        }

        static void waitSearchStarted() throws InterruptedException {
            assertTrue(startedLatch.get().await(60, TimeUnit.SECONDS));
        }

        @Override
        public void onIndexModule(IndexModule indexModule) {
            indexModule.addSearchOperationListener(new SearchOperationListener() {
                @Override
                public void onNewReaderContext(ReaderContext readerContext) {
                    assertThat(readerContext, not(instanceOf(LegacyReaderContext.class)));
                }

                @Override
                public void onPreQueryPhase(SearchContext searchContext) {
                    startedLatch.get().countDown();
                    final CountDownLatch latch = queryLatch.get();
                    if (latch != null) {
                        try {
                            assertTrue(latch.await(60, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            throw new AssertionError(e);
                        }
                    }
                }
            });
            super.onIndexModule(indexModule);
        }
    }

}
