/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.rest.action.mycount;

import org.elasticsearch.test.ESIntegTestCase;

import java.util.List;

import static org.hamcrest.Matchers.equalTo;

public class MyCountIT extends ESIntegTestCase {

    public void testEmpty() {
        client().prepareIndex("test1").setSource("foo", "bar").get();
        MyCountActionResponse resp = client().execute(MyCountTransportAction.TYPE, new MyCountActionRequest(List.of())).actionGet();
        assertThat(resp.getCount(), equalTo(0L));
    }
}
