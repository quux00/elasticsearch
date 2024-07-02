/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.rest.action.mycount;

import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESIntegTestCase;

import java.util.List;

import static org.hamcrest.Matchers.equalTo;

public class MyCountIT extends ESIntegTestCase {

    public void testEmpty() {
        String indexName = "test1";
        client().admin().indices().prepareCreate(indexName).get();
        ensureGreen(indexName);
        MyCountActionResponse resp = client().execute(MyCountTransportAction.TYPE, new MyCountActionRequest(List.of())).actionGet();
        assertThat(resp.getCount(), equalTo(0L));
    }

    public void testSimpleNoReplicas() {
        internalCluster().ensureAtLeastNumDataNodes(2); // add later
        String indexName = "test2";
        client().admin()
            .indices()
            .prepareCreate(indexName)
            .setSettings(Settings.builder().put(IndexMetadata.INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 0)) // add later
            .get();
        ensureGreen(indexName);
        long numDocs = randomIntBetween(1, 100);
        for (int i = 0; i < numDocs; i++) {
            client().prepareIndex(indexName).setId("id-" + i).setSource("field", Integer.toString(i)).get();
        }
        client().admin().indices().prepareRefresh(indexName).get();
        MyCountActionResponse resp = client().execute(MyCountTransportAction.TYPE, new MyCountActionRequest(List.of())).actionGet();
        assertThat(resp.getCount(), equalTo(numDocs));
    }

    public void testSimpleWithReplicas() {
        internalCluster().ensureAtLeastNumDataNodes(2);
        String indexName = "test2";
        client().admin().indices().prepareCreate(indexName).get();
        ensureGreen(indexName);
        long numDocs = randomIntBetween(1, 100);
        System.err.println(numDocs);
        for (int i = 0; i < numDocs; i++) {
            client().prepareIndex(indexName).setId("id-" + i).setSource("field", Integer.toString(i)).get();
        }
        client().admin().indices().prepareRefresh(indexName).get();
        MyCountActionResponse resp = client().execute(MyCountTransportAction.TYPE, new MyCountActionRequest(List.of())).actionGet();
        assertThat(resp.getCount(), equalTo(numDocs));
    }

}
