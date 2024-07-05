/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.mycount;

import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESIntegTestCase;

import java.util.List;

import static org.hamcrest.Matchers.equalTo;

public class MyCountIT extends ESIntegTestCase {

    public void testEmpty() {
        MyCountActionRequest request = new MyCountActionRequest(List.of());
        MyCountActionResponse resp = client().execute(MyCountTransportAction.TYPE, request).actionGet();
        assertNotNull("Response should not not null", resp);
        assertThat(resp.getCount(), equalTo(0L));
    }

    public void testDocCountWithNoReplicas() {
        String indexName = "myindex";
        client().admin()
            .indices()
            .prepareCreate(indexName)
            .setSettings(Settings.builder().put(IndexMetadata.INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 0))
            .get();
        ensureGreen(indexName);
        long numDocs = randomIntBetween(1, 100);
        for (int i = 0; i < numDocs; i++) {
            client().prepareIndex(indexName).setId("id-" + i).setSource("myfield", Integer.toString(i)).get();
        }
        client().admin().indices().prepareRefresh(indexName).get();

        MyCountActionResponse resp = client().execute(MyCountTransportAction.TYPE, new MyCountActionRequest(List.of())).actionGet();
        assertNotNull("Response should not not null", resp);
        assertThat(resp.getCount(), equalTo(numDocs));
    }

    public void testDocCountWithReplicas() {
        internalCluster().ensureAtLeastNumDataNodes(3);
        String indexName = "myindex";
        client().admin()
            .indices()
            .prepareCreate(indexName)
            .setSettings(Settings.builder().put(IndexMetadata.INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 1))
            .get();
        ensureGreen(indexName);
        long numDocs = randomIntBetween(1, 100);
        for (int i = 0; i < numDocs; i++) {
            client().prepareIndex(indexName).setId("id-" + i).setSource("myfield", Integer.toString(i)).get();
        }
        client().admin().indices().prepareRefresh(indexName).get();

        MyCountActionResponse resp = client().execute(MyCountTransportAction.TYPE, new MyCountActionRequest(List.of())).actionGet();
        assertNotNull("Response should not not null", resp);
        assertThat(resp.getCount(), equalTo(numDocs));
    }

}
