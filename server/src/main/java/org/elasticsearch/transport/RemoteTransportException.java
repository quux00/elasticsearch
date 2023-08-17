/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.transport;

import org.elasticsearch.ElasticsearchWrapperException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.transport.TransportAddress;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * A remote exception for an action. A wrapper exception around the actual remote cause and does not fill the
 * stack trace. The clusterAlias where the exception occurred can optionally be set on the Exception.
 */
public class RemoteTransportException extends ActionTransportException implements ElasticsearchWrapperException {

    private final String clusterAlias;

    public RemoteTransportException(String msg, Throwable cause) {
        super(msg, null, null, cause);
        this.clusterAlias = null;
    }

    /**
     * @param msg error message
     * @param cause underlying cause
     * @param clusterAlias cluster alias (from local cluster settings) for cluster with this Exception
     */
    public RemoteTransportException(String msg, Throwable cause, String clusterAlias) {
        super(msg, null, null, cause);
        this.clusterAlias = clusterAlias;
    }

    public RemoteTransportException(String name, TransportAddress address, String action, Throwable cause) {
        super(name, address, action, cause);
        this.clusterAlias = null;
    }

    public RemoteTransportException(String name, InetSocketAddress address, String action, Throwable cause) {
        super(name, address, action, null, cause);
        this.clusterAlias = null;
    }

    public RemoteTransportException(StreamInput in) throws IOException {
        super(in);
        this.clusterAlias = null;
        // if (in.getTransportVersion().onOrAfter(TransportVersion.V_8_500_064)) {
        // this.clusterAlias = in.readOptionalString();
        // } else {
        // this.clusterAlias = null;
        // }
    }

    @Override
    protected void writeTo(StreamOutput out, Writer<Throwable> nestedExceptionsWriter) throws IOException {
        super.writeTo(out, nestedExceptionsWriter);
        // if (out.getTransportVersion().before(TransportVersion.V_8_500_064)) {
        // out.writeOptionalString(clusterAlias);
        // }
    }

    @Override
    public Throwable fillInStackTrace() {
        // no need for stack trace here, we always have cause
        return this;
    }

    public String getClusterAlias() {
        return clusterAlias;
    }
}
