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

        for (DiscoveryNode node : nodes) {
            // for every node, send a node level request and sum all of them
            transportService.sendChildRequest(
                node,
                NODE_LEVEL_ACTION_NAME,
                new NodeLevelRequest(),
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
                        if (countDown.countDown()) {
                            listener.onResponse(new MyCountActionResponse(totalDocCount.get()));
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
        public NodeLevelRequest() {
            // TODO: Need this?
        }

        public NodeLevelRequest(StreamInput in) throws IOException {
            super(in);
            // FIXME
        }
    }

    static class NodeLevelResponse extends TransportResponse {

        private final long count;

        NodeLevelResponse(long count) {
            this.count = count;
        }

        NodeLevelResponse(StreamInput in) throws IOException {
            super(in);
            this.count = in.readVLong();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeVLong(count);
        }
    }

    static class NodeLevelRequestHandler implements TransportRequestHandler<NodeLevelRequest> {

        private final IndicesService indicesService;

        NodeLevelRequestHandler(IndicesService indicesService) {
            this.indicesService = indicesService;
        }

        @Override
        public void messageReceived(NodeLevelRequest request, TransportChannel channel, Task task) throws Exception {
            long total = 0;
            for (IndexService indexService : indicesService) {
                for (IndexShard indexShard : indexService) {
                    total += indexShard.docStats().getCount();
                }
            }
            channel.sendResponse(new NodeLevelResponse(total));
        }
    }
}
