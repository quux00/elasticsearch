/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.example;

import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.refresh.RefreshResponse;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESIntegTestCase;

import java.util.List;

import static org.hamcrest.Matchers.equalTo;

public class MyDocCountIT extends ESIntegTestCase {

    public void testEmpty() {
        if (randomBoolean()) {
            CreateIndexResponse createIndexResponse = client().admin().indices().prepareCreate("myidx").get();
            System.err.println(createIndexResponse.index());
        }

        MyDocCountResponse resp = client().execute(MyDocCountAction.INSTANCE, new MyDocCountRequest(List.of())).actionGet();
        System.err.println(resp);

        assertThat(resp.getCount(), equalTo(0L));
    }

    public void testDocCountAgainstOneIndex() {
        String idxName = "myidx2";
        client().admin().indices().prepareCreate(idxName).get();
        ensureGreen(idxName);

        long numDocs = randomIntBetween(10, 100);
        for (int i = 0; i < numDocs; i++) {
            client().prepareIndex(idxName).setId("id-" + i).setSource("field", "" + i).get();
        }
        RefreshResponse refreshResponse = client().admin().indices().prepareRefresh(idxName).get();
        System.err.println("numshards: " + refreshResponse.getTotalShards());

        MyDocCountResponse resp = client().execute(MyDocCountAction.INSTANCE, new MyDocCountRequest(List.of())).actionGet();
        System.err.println(resp.status());

        assertThat(resp.getCount(), equalTo(numDocs));
    }

    public void testDocCountAgainstOneIndexMultipleNodesWith2Replicas() {
        String idxName = "myidx3";

        internalCluster().ensureAtLeastNumDataNodes(2);

        client().admin()
            .indices()
            .prepareCreate(idxName)
            .setSettings(Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 2).build())
            .get();
        // ensureGreen(idxName);
        ensureYellow(idxName);

        long numDocs = randomIntBetween(10, 100);
        for (int i = 0; i < numDocs; i++) {
            client().prepareIndex(idxName).setId("id-" + i).setSource("field", "" + i).get();
        }
        RefreshResponse refreshResponse = client().admin().indices().prepareRefresh(idxName).get();
        System.err.println("numshards: " + refreshResponse.getTotalShards());

        MyDocCountResponse resp = client().execute(MyDocCountAction.INSTANCE, new MyDocCountRequest(List.of())).actionGet();
        System.err.println(resp.status());

        assertThat(resp.getCount(), equalTo(numDocs));
    }

}
