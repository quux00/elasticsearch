/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.example;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class TransportMyDocCountAction extends HandledTransportAction<MyDocCountRequest, MyDocCountResponse> {

    private static final Logger logger = LogManager.getLogger(TransportMyDocCountAction.class);

    private final TransportService transportService;
    private final ClusterService clusterService;
    private final IndicesService indicesService;
    private final String nodeLevelActionName;

    /**
     * This ctor is only called once at startup during the DepInjection wiring.
     */
    @Inject
    public TransportMyDocCountAction(
        TransportService transportService,
        ClusterService clusterService,
        IndicesService indicesService,
        ActionFilters actionFilters
    ) {
        // Note - the MyDocCountRequest::new last arg specifies what object/ctor to use when deserializing to a POJO
        super(MyDocCountAction.ACTION_NAME, transportService, actionFilters, MyDocCountRequest::new);
        this.transportService = transportService;
        this.clusterService = clusterService;
        this.indicesService = indicesService;

        /**
         * Registers a new request handler
         *
         * @param action         The action the request handler is associated with
         * @param executor       The executor the request handling will be executed on
         * @param requestReader  a callable to be used construct new instances for streaming
         * @param handler        The handler itself that implements the request handling
         */

        // need a different action name here because the super() call above already registered this name (? not fully understanding why?)
        this.nodeLevelActionName = MyDocCountAction.ACTION_NAME + "2";
        transportService.registerRequestHandler(
            nodeLevelActionName,
            ThreadPool.Names.GET,
            NodeLevelRequest::new,
            new NodeLevelRequestHandler(indicesService)
        );
    }

    /**
     * This method is called after the network bytes have been deserialized to MyDocCountRequest::new (specified in ctor above)
     * @param task
     * @param request
     * @param mainListener
     */
    @Override
    protected void doExecute(Task task, MyDocCountRequest request, ActionListener<MyDocCountResponse> mainListener) {
        // get list of nodes to execute action on
        ClusterState clusterState = clusterService.state();
        final AtomicLong totalDocCount = new AtomicLong(0);
        final Map<String, Long> perNodeCount = new HashMap<String, Long>();
        DiscoveryNodes nodes = clusterState.nodes();
        CountDown countDown = new CountDown(nodes.size());
        for (DiscoveryNode node : nodes) {
            // for every node, we need to send a node-level request and sum the results
            transportService.sendChildRequest(
                node,
                nodeLevelActionName,
                new NodeLevelRequest(),
                task,
                TransportRequestOptions.EMPTY,
                new TransportResponseHandler<NodeLevelResponse>() {
                    @Override
                    public void handleResponse(NodeLevelResponse response) {
                        totalDocCount.getAndAdd(response.getCount());
                        perNodeCount.put(node.getName(), response.getCount());
                        if (countDown.countDown()) {
                            mainListener.onResponse(new MyDocCountResponse(totalDocCount.get(), perNodeCount));
                        }
                    }

                    @Override
                    public void handleException(TransportException exp) {
                        logger.warn("Failure in DocCountTransportAction: {}", exp);
                        if (countDown.countDown()) {
                            mainListener.onResponse(new MyDocCountResponse(totalDocCount.get(), perNodeCount));
                        }
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
        public NodeLevelRequest() {}

        public NodeLevelRequest(StreamInput in) throws IOException {
            super(in);
        }
    }

    static class NodeLevelResponse extends TransportResponse {
        private final long count;

        public NodeLevelResponse(long count) {
            this.count = count;
        }

        public NodeLevelResponse(StreamInput in) throws IOException {
            super(in);
            this.count = in.readVLong();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeVLong(count);
        }

        public long getCount() {
            return count;
        }
    }

    static class NodeLevelRequestHandler implements TransportRequestHandler<NodeLevelRequest> {

        private final IndicesService indicesService;

        public NodeLevelRequestHandler(IndicesService indicesService) {
            this.indicesService = indicesService;
        }

        @Override
        public void messageReceived(NodeLevelRequest request, TransportChannel channel, Task task) throws Exception {
            long totCount = 0;
            for (IndexService indexService : indicesService) {
                for (IndexShard indexShard : indexService) {
                    System.err.printf(
                        "shard %s; primary: %s; count: %d%n",
                        indexShard.routingEntry(),
                        indexShard.routingEntry().primary(),
                        indexShard.docStats().getCount()
                    );
                    if (indexShard.routingEntry().primary()) {
                        totCount += indexShard.docStats().getCount();
                    }
                }
            }
            System.err.println("Total count on node (before send to coord): " + totCount);
            channel.sendResponse(new NodeLevelResponse(totCount));  // send the response to the coordinator
        }
    }

}
