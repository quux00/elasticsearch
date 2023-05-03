/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.example;

import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.common.Strings;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.Scope;
import org.elasticsearch.rest.ServerlessScope;
import org.elasticsearch.rest.action.RestBuilderListener;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.rest.RestRequest.Method.GET;

@ServerlessScope(Scope.PUBLIC)
public class RestMyDocCountAction extends BaseRestHandler {

    @Override
    public List<Route> routes() {
        return List.of(new Route(GET, "/_my_doc_count"));
    }

    @Override
    public String getName() {
        return "my_doc_count_action";
    }

    @Override
    public RestChannelConsumer prepareRequest(final RestRequest request, final NodeClient client) throws IOException {
        // create a Transport action request _from_ the REST request
        List<String> indicesList = Arrays.stream(Strings.splitStringByCommaToArray(request.param("index"))).toList();
        MyDocCountRequest docCountRequest = new MyDocCountRequest(indicesList);

        // SearchRequest countRequest = new SearchRequest(Strings.splitStringByCommaToArray(request.param("index")));
        // countRequest.indicesOptions(IndicesOptions.fromRequest(request, countRequest.indicesOptions()));
        // SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder().size(0).trackTotalHits(true);
        // countRequest.source(searchSourceBuilder);
        // request.withContentOrSourceParamParserOrNull(parser -> {
        // if (parser == null) {
        // QueryBuilder queryBuilder = RestActions.urlParamsToQueryBuilder(request);
        // if (queryBuilder != null) {
        // searchSourceBuilder.query(queryBuilder);
        // }
        // } else {
        // searchSourceBuilder.query(RestActions.getQueryContent(parser));
        // }
        // });
        // countRequest.routing(request.param("routing"));
        // float minScore = request.paramAsFloat("min_score", -1f);
        // if (minScore != -1f) {
        // searchSourceBuilder.minScore(minScore);
        // }
        //
        // countRequest.preference(request.param("preference"));
        //
        // final int terminateAfter = request.paramAsInt("terminate_after", DEFAULT_TERMINATE_AFTER);
        // searchSourceBuilder.terminateAfter(terminateAfter);
        return channel -> client.executeLocally(MyDocCountAction.INSTANCE, docCountRequest, new RestBuilderListener<>(channel) {
            // builds the RestResponse from the transport responses (?)
            @Override
            public RestResponse buildResponse(MyDocCountResponse response, XContentBuilder builder) throws Exception {
                builder.value(response);
                return new RestResponse(response.status(), builder);
            }
        });
    }

}
