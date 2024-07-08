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
        assertThat(resp.getCountByIndex().size(), equalTo(0));
    }

    public void testDocCountWithNoReplicasAndMultipleIndices() {
        String indexName1 = "myindex";
        String indexName2 = "stas";
        client().admin()
            .indices()
            .prepareCreate(indexName1)
            .setSettings(Settings.builder().put(IndexMetadata.INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 0))
            .get();
        client().admin()
            .indices()
            .prepareCreate(indexName2)
            .setSettings(Settings.builder().put(IndexMetadata.INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 0))
            .get();
        ensureGreen(indexName1);
        ensureGreen(indexName2);
        long numDocs = randomIntBetween(1, 100);
        for (int i = 0; i < numDocs; i++) {
            client().prepareIndex(indexName1).setId("id-" + i).setSource("myfield", Integer.toString(i)).get();
            client().prepareIndex(indexName2).setId("id-" + i).setSource("other_field", Integer.toString(i)).get();
        }
        client().admin().indices().prepareRefresh(indexName1).get();
        client().admin().indices().prepareRefresh(indexName2).get();

        {
            MyCountActionResponse resp = client().execute(MyCountTransportAction.TYPE, new MyCountActionRequest(List.of(indexName1)))
                .actionGet();
            assertNotNull("Response should not not null", resp);
            assertThat(resp.getCount(), equalTo(numDocs));
            assertThat(resp.getCountByIndex().size(), equalTo(1));
            System.err.println(numDocs);
            assertThat(resp.getCountByIndex().get(indexName1), equalTo(numDocs));
        }

        {
            MyCountActionResponse resp = client().execute(
                MyCountTransportAction.TYPE,
                new MyCountActionRequest(List.of(indexName1, indexName2))
            ).actionGet();
            assertNotNull("Response should not not null", resp);
            assertThat(resp.getCount(), equalTo(numDocs * 2));
            assertThat(resp.getCountByIndex().size(), equalTo(2));
            assertThat(resp.getCountByIndex().get(indexName1), equalTo(numDocs));
            assertThat(resp.getCountByIndex().get(indexName2), equalTo(numDocs));
        }
    }

    public void testSimpleNoReplicasWildCards() {
        internalCluster().ensureAtLeastNumDataNodes(2);
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
        MyCountActionResponse resp = client().execute(MyCountTransportAction.TYPE, new MyCountActionRequest(List.of("*"))).actionGet();
        assertThat(resp.getCount(), equalTo(numDocs));
        assertThat(resp.getCountByIndex().size(), equalTo(1));
        assertThat(resp.getCountByIndex().get(indexName), equalTo(numDocs));
    }
}
