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
public class FatalCCSException extends RuntimeException {

    private final String clusterAlias;

    public FatalCCSException(String clusterAlias, Throwable cause) {
        super(cause);
        assert cause != null : "Cause should always be set on FatalCCSException";
        this.clusterAlias = clusterAlias;
    }

    public String getClusterAlias() {
        return clusterAlias;
    }
}
