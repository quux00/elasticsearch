/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.mycount;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.util.concurrent.CountDown;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.tasks.CancellableTask;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportChannel;
import org.elasticsearch.transport.TransportException;
import org.elasticsearch.transport.TransportRequestHandler;
import org.elasticsearch.transport.TransportRequestOptions;
import org.elasticsearch.transport.TransportResponseHandler;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

public class MyCountTransportAction extends HandledTransportAction<MyCountActionRequest, MyCountActionResponse> {

    private static final Logger logger = LogManager.getLogger(MyCountTransportAction.class);

    public static final String NAME = "indices:data/read/my_count";
    private static final String NODE_LEVEL_ACTION_NAME = NAME + "[n]";

    public static final ActionType<MyCountActionResponse> TYPE = new ActionType<>(NAME);

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
            MyCountActionRequest::new,
            new NodeLevelRequestHandler(indicesService)
        );
    }

    @Override
    protected void doExecute(Task task, MyCountActionRequest request, ActionListener<MyCountActionResponse> listener) {
        // on this thread kick off actions to do some processing either locally or on other nodes and passing ^^ listener
        // callbacks will wait for responses
        // when all responses come back -> listener.onResponse(FinalResponseObject)

        ClusterState clusterState = clusterService.state();
        DiscoveryNodes nodes = clusterState.nodes();

        final AtomicLong totalDocCount = new AtomicLong(0);
        CountDown countDown = new CountDown(nodes.size());

        for (DiscoveryNode node : nodes) {
            transportService.sendChildRequest(
                node,
                NODE_LEVEL_ACTION_NAME,
                request,
                task,
                TransportRequestOptions.EMPTY,
                new TransportResponseHandler<MyCountActionResponse>() {
                    @Override
                    public Executor executor() {
                        return threadPool.generic();
                    }

                    @Override
                    public void handleResponse(MyCountActionResponse response) {
                        totalDocCount.addAndGet(response.getCount());
                        if (countDown.countDown()) {
                            listener.onResponse(new MyCountActionResponse(totalDocCount.get()));
                        }
                    }

                    @Override
                    public void handleException(TransportException exc) {
                        if (countDown.countDown()) {
                            listener.onResponse(new MyCountActionResponse(totalDocCount.get()));
                        }
                        logger.debug("Error in MyCount: {}", exc);
                    }

                    @Override
                    public MyCountActionResponse read(StreamInput in) throws IOException {
                        return new MyCountActionResponse(in);
                    }
                }
            );
        }
    }

    static class NodeLevelRequestHandler implements TransportRequestHandler<MyCountActionRequest> {

        private final IndicesService indicesService;

        NodeLevelRequestHandler(IndicesService indicesService) {
            this.indicesService = indicesService;
        }

        @Override
        public void messageReceived(MyCountActionRequest request, TransportChannel channel, Task task) throws Exception {
            // implement the logic of actually getting the doc count from the Lucene indices

            CancellableTask cancellableTask = (CancellableTask) task;

            long total = 0;

            for (IndexService indexService : indicesService) {
                for (IndexShard indexShard : indexService) {
                    total += indexShard.docStats().getCount();
                }
                if (cancellableTask.isCancelled()) {
                    // take some action
                }
            }

            channel.sendResponse(new MyCountActionResponse(total));
        }
    }

}
