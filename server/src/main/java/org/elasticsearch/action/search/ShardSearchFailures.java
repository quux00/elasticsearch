/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.search;

/**
 * TODO: DOCUMENT ME
 */
public class ShardSearchFailures {  // TODO: convert to Record?

    private final int numFailures;
    private final ShardSearchFailure[] failures;

    public ShardSearchFailures(int numFailures, ShardSearchFailure[] failures) {
        this.numFailures = numFailures;
        this.failures = failures;
    }

    public int getNumFailures() {
        return numFailures;
    }

    public ShardSearchFailure[] getFailures() {
        return failures;
    }
}
