/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.rest.action.mycount;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.RemoteClusterActionType;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.util.concurrent.CountDown;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportChannel;
import org.elasticsearch.transport.TransportException;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportRequestHandler;
import org.elasticsearch.transport.TransportRequestOptions;
import org.elasticsearch.transport.TransportResponse;
import org.elasticsearch.transport.TransportResponseHandler;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

public class MyCountTransportAction extends HandledTransportAction<MyCountActionRequest, MyCountActionResponse> {

    private static final Logger logger = LogManager.getLogger(MyCountActionRequest.class);

    // name of the handler for the my_count request
    public static final String NAME = "indices:data/read/my_count";
    private static final String NODE_LEVEL_ACTION_NAME = NAME + "[n]";
    public static final ActionType<MyCountActionResponse> TYPE = new ActionType<>(NAME);
    public static final RemoteClusterActionType<MyCountActionResponse> REMOTE_TYPE = new RemoteClusterActionType<>(
        NAME,
        MyCountActionResponse::new
    );

    private final TransportService transportService;
    private final ClusterService clusterService;
    private final IndicesService indicesService;
    private final ThreadPool threadPool;

    @Inject
    public MyCountTransportAction(
        TransportService transportService,
        ClusterService clusterService,
        IndicesService indicesService,
        ThreadPool threadPool,
        ActionFilters actionFilters
    ) {
        // the super call registers ths handler under NAME with the TransportService
        super(NAME, transportService, actionFilters, MyCountActionRequest::new, EsExecutors.DIRECT_EXECUTOR_SERVICE);
        this.transportService = transportService;
        this.clusterService = clusterService;
        this.indicesService = indicesService;
        this.threadPool = threadPool;
        transportService.registerRequestHandler(
            NODE_LEVEL_ACTION_NAME,
            EsExecutors.DIRECT_EXECUTOR_SERVICE,
            false,
            false,
            NodeLevelRequest::new,
            new NodeLevelRequestHandler(indicesService)
        );
    }

    @Override
    protected void doExecute(Task task, MyCountActionRequest request, ActionListener<MyCountActionResponse> listener) {
        ClusterState clusterState = clusterService.state();
        DiscoveryNodes nodes = clusterState.nodes();
        CountDown countDown = new CountDown(nodes.size());
        final AtomicLong totalDocCount = new AtomicLong();
        final Map<String, Long> countByIndices = new ConcurrentHashMap<>();

        for (DiscoveryNode node : nodes) {
            // for every node, send a node level request and sum all of them
            transportService.sendChildRequest(
                node,
                NODE_LEVEL_ACTION_NAME,
                new NodeLevelRequest(request.getIndices()),
                task,
                TransportRequestOptions.EMPTY,
                new TransportResponseHandler<NodeLevelResponse>() {
                    @Override
                    public Executor executor() {
                        return threadPool.generic();
                    }

                    @Override
                    public void handleResponse(NodeLevelResponse response) {
                        totalDocCount.addAndGet(response.count);
                        for (Map.Entry<String, Long> entry : response.byIndexCounts.entrySet()) {
                            countByIndices.compute(entry.getKey(), (k, v) -> v == null ? 1L : v + entry.getValue());
                        }
                        if (countDown.countDown()) {
                            listener.onResponse(new MyCountActionResponse(totalDocCount.get(), response.byIndexCounts));
                        }
                    }

                    @Override
                    public void handleException(TransportException exc) {
                        // (mostly) ignore errors for now - just log them
                        countDown.countDown();
                        logger.debug("Error in {}: {}", NODE_LEVEL_ACTION_NAME, exc);
                    }

                    @Override
                    public NodeLevelResponse read(StreamInput in) throws IOException {
                        return new NodeLevelResponse(in);
                    }
                }
            );
        }
    }

    static class NodeLevelRequest extends TransportRequest {
        final List<String> indices;

        NodeLevelRequest(List<String> indices) {
            this.indices = indices;
        }

        NodeLevelRequest(StreamInput in) throws IOException {
            super(in);
            this.indices = in.readStringCollectionAsImmutableList();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeStringCollection(indices);
        }

        public List<String> getIndices() {
            return indices;
        }
    }

    static class NodeLevelResponse extends TransportResponse {
        private final long count;
        private final Map<String, Long> byIndexCounts; // add in v3 along with new TransportVersion!

        NodeLevelResponse(long count, Map<String, Long> byIndexCounts) {
            this.count = count;
            this.byIndexCounts = byIndexCounts;
        }

        NodeLevelResponse(StreamInput in) throws IOException {
            super(in);
            this.count = in.readVLong();
            // note: wrap this in new TransportVersion check
            this.byIndexCounts = in.readMap(StreamInput::readString, StreamInput::readVLong);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeVLong(count);
            // note: wrap this in new TransportVersion check
            out.writeMap(byIndexCounts, StreamOutput::writeString, StreamOutput::writeVLong);
        }
    }

    static class NodeLevelRequestHandler implements TransportRequestHandler<NodeLevelRequest> {

        private final IndicesService indicesService;

        NodeLevelRequestHandler(IndicesService indicesService) {
            this.indicesService = indicesService;
        }

        @Override
        public void messageReceived(NodeLevelRequest request, TransportChannel channel, Task task) throws Exception {
            HashSet<String> indicesToCount = new HashSet<>(request.getIndices());
            Map<String, Long> byIndices = new HashMap<>();
            System.err.println("indicesToCount: " + indicesToCount);
            long total = 0;
            for (IndexService indexService : indicesService) {
                for (IndexShard indexShard : indexService) {
                    // version 3
                    if (indexShard.routingEntry().primary()) {
                        String indexName = indexService.index().getName();
                        if (indicesToCount.isEmpty() || indicesToCount.contains(indexName)) {
                            total += indexShard.docStats().getCount();
                            if (indicesToCount.isEmpty() == false) {
                                byIndices.compute(indexName, (k, v) -> v == null ? 1L : v + indexShard.docStats().getCount());
                            }
                        }
                    }

                    // version 2
                    // if (indexShard.routingEntry().primary()) {
                    // total += indexShard.docStats().getCount();
                    // }

                    // // version 1
                    // long count = indexShard.docStats().getCount();
                    // System.err.printf("shard: %s; count=%d\n", indexShard.routingEntry(), count);
                    // total += count;
                }
            }
            System.err.println("---> message received; total: " + total);
            channel.sendResponse(new NodeLevelResponse(total, byIndices));
        }
    }
}
