/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.common.logback;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.SSLSession;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.Scheme;

/**
 * A built-in property exported by {@link RequestContextExportingAppender}.
 *
 * @see RequestContextExportingAppender#addBuiltIn(BuiltInProperty)
 */
public enum BuiltInProperty {
    /**
     * {@code "remote.host"} - the host name part of the remote socket address. Unavailable if the connection
     * is not established yet.
     */
    REMOTE_HOST("remote.host"),
    /**
     * {@code "remote.ip"} - the IP address part of the remote socket address. Unavailable if the connection
     * is not established yet.
     */
    REMOTE_IP("remote.ip"),
    /**
     * {@code "remote.port"} - the port number part of the remote socket address. Unavailable if the connection
     * is not established yet.
     */
    REMOTE_PORT("remote.port"),
    /**
     * {@code "local.host"} - the host name part of the local socket address. Unavailable if the connection
     * is not established yet.
     */
    LOCAL_HOST("local.host"),
    /**
     * {@code "local.ip"} - the IP address part of the local socket address. Unavailable if the connection
     * is not established yet.
     */
    LOCAL_IP("local.ip"),
    /**
     * {@code "local.port"} - the port number part of the local socket address. Unavailable if the connection
     * is not established yet.
     */
    LOCAL_PORT("local.port"),
    /**
     * {@code "scheme"} - the scheme of the request, represented by {@link Scheme#uriText()}.
     * e.g. {@code "tbinary+h2"}
     */
    SCHEME("scheme"),
    /**
     * {@code "elapsed_nanos"} - the amount of time in nanoseconds taken to handle the request. Unavailable if
     * the request was not handled completely yet.
     */
    ELAPSED_NANOS("elapsed_nanos"),
    /**
     * {@code "req.direction"} - the direction of the request, which is {@code "INBOUND"} for servers and
     *  {@code "OUTBOUND"} for clients.
     */
    REQ_DIRECTION("req.direction"),
    /**
     * {@code "req.authority"} - the authority of the request, represented as {@code "<hostname>[:<port>]"}.
     * The port number is omitted when it is same with the default port number of the current {@link Scheme}.
     */
    REQ_AUTHORITY("req.authority"),
    /**
     * {@code "req.path"} - the path of the request.
     */
    REQ_PATH("req.path"),
    /**
     * {@code "req.query"} - the query of the request.
     */
    REQ_QUERY("req.query"),
    /**
     * {@code "req.method"} - the method name of the request. e.g. {@code "GET"} and {@code "POST"}
     */
    REQ_METHOD("req.method"),
    /**
     * {@code "req.rpc_method"} - the RPC method name of the request. Unavailable if the current request is not
     * an RPC request or is not decoded yet.
     */
    REQ_RPC_METHOD("req.rpc_method"),
    /**
     * {@code "req.rpc_params"} - the RPC parameter list, represented by {@link Arrays#toString(Object...)}.
     * Unavailable if the current request is not an RPC request or is not decoded yet.
     */
    REQ_RPC_PARAMS("req.rpc_params"),
    /**
     * {@code "req.content_length"} - the byte-length of the request content. Unavailable if the current
     * request is not fully received yet.
     */
    REQ_CONTENT_LENGTH("req.content_length"),
    /**
     * {@code "res.status_code"} - the protocol-specific integer representation of the response status code.
     * Unavailable if the current response is not fully sent yet.
     */
    RES_STATUS_CODE("res.status_code"),
    /**
     * {@code "res.rpc_result"} - the RPC result value of the response. Unavailable if the current response
     * is not fully sent yet.
     */
    RES_RPC_RESULT("res.rpc_result"),
    /**
     * {@code "res.content_length"} - the byte-length of the response content. Unavailable if the current
     * response is not fully sent yet.
     */
    RES_CONTENT_LENGTH("res.content_length"),
    /**
     * {@code "tls.session_id"} - the hexadecimal representation of the current
     * {@linkplain SSLSession#getId() TLS session ID}. Unavailable if TLS handshake is not finished or
     * the connection is not a TLS connection.
     */
    TLS_SESSION_ID("tls.session_id"),
    /**
     * {@code "tls.cipher"} - the current {@linkplain SSLSession#getCipherSuite() TLS cipher suite}.
     * Unavailable if TLS handshake is not finished or the connection is not a TLS connection.
     * e.g. {@code "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384"}
     */
    TLS_CIPHER("tls.cipher"),
    /**
     * {@code "tls.proto"} - the current {@linkplain SSLSession#getProtocol()} TLS protocol}.
     * Unavailable if TLS handshake is not finished or the connection is not a TLS connection.
     * e.g. {@code "TLSv1.2"}
     */
    TLS_PROTO("tls.proto");

    private static final Map<String, BuiltInProperty> mdcKeyToEnum;

    static {
        ImmutableMap.Builder<String, BuiltInProperty> builder = ImmutableMap.builder();
        for (BuiltInProperty k : BuiltInProperty.values()) {
            builder.put(k.mdcKey, k);
        }
        mdcKeyToEnum = builder.build();
    }

    static Optional<BuiltInProperty> findByMdcKey(String mdcKey) {
        return Optional.ofNullable(mdcKeyToEnum.get(mdcKey));
    }

    final String mdcKey;

    BuiltInProperty(String mdcKey) {
        this.mdcKey = mdcKey;
    }
}
