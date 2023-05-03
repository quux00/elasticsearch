/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.example;

import org.elasticsearch.action.ActionType;

public class MyDocCountAction extends ActionType<MyDocCountResponse> {
    public static final String ACTION_NAME = "indices:data/read/doc_count";

    public static final MyDocCountAction INSTANCE = new MyDocCountAction();

    MyDocCountAction() {
        super(ACTION_NAME, MyDocCountResponse::new);
    }
}
