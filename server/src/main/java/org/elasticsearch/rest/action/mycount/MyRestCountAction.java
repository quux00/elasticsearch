/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.rest.action.mycount;

import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.common.Strings;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.Scope;
import org.elasticsearch.rest.ServerlessScope;
import org.elasticsearch.rest.action.RestCancellableNodeClient;
import org.elasticsearch.rest.action.RestToXContentListener;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.elasticsearch.rest.RestRequest.Method.GET;

@ServerlessScope(Scope.PUBLIC)
public class MyRestCountAction extends BaseRestHandler {
    @Override
    public List<Route> routes() {
        return List.of(
            new Route(GET, "/_my_count"),
            new Route(GET, "/_my_count/{name}")  // add later
        );
    }

    @Override
    public String getName() {
        return "my_count_action";
    }

    @Override
    public RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        // throw new UnsupportedOperationException("Hello there");
        String[] indices = Strings.splitStringByCommaToArray(request.param("name"));  // add later

        // create Transport action request from the REST request
        MyCountActionRequest countRequest = new MyCountActionRequest(Arrays.asList(indices));

        return channel -> new RestCancellableNodeClient(client, request.getHttpChannel()).admin()
            .indices()
            .execute(MyCountTransportAction.TYPE, countRequest, new RestToXContentListener<>(channel));
    }
}
