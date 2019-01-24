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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.time.Duration;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;

import io.netty.handler.codec.Headers;
import io.netty.util.AsciiString;

/**
 * Provides information about a {@link Request}, its {@link Response} and its related utilities.
 * Every client request has its own {@link ClientRequestContext} instance.
 */
public interface ClientRequestContext extends RequestContext {

    /**
     * Returns a new {@link ClientRequestContext} created from the specified {@link HttpRequest}.
     * Note that it is not usually required to create a new context by yourself, because Armeria
     * will always provide a context object for you. However, it may be useful in some cases such as
     * unit testing.
     *
     * @see ClientRequestContextBuilder
     */
    static ClientRequestContext of(HttpRequest request) {
        return ClientRequestContextBuilder.of(request).build();
    }

    /**
     * Returns a new {@link ClientRequestContext} created from the specified {@link RpcRequest} and URI.
     * Note that it is not usually required to create a new context by yourself, because Armeria
     * will always provide a context object for you. However, it may be useful in some cases such as
     * unit testing.
     *
     * @see ClientRequestContextBuilder
     */
    static ClientRequestContext of(RpcRequest request, String uri) {
        return ClientRequestContextBuilder.of(request, URI.create(requireNonNull(uri, "uri"))).build();
    }

    /**
     * Returns a new {@link ClientRequestContext} created from the specified {@link RpcRequest} and {@link URI}.
     * Note that it is not usually required to create a new context by yourself, because Armeria
     * will always provide a context object for you. However, it may be useful in some cases such as
     * unit testing.
     *
     * @see ClientRequestContextBuilder
     */
    static ClientRequestContext of(RpcRequest request, URI uri) {
        return ClientRequestContextBuilder.of(request, uri).build();
    }

    @Override
    ClientRequestContext newDerivedContext();

    @Override
    ClientRequestContext newDerivedContext(Request request);

    /**
     * Returns the remote {@link Endpoint} of the current {@link Request}.
     */
    Endpoint endpoint();

    /**
     * Returns the {@link ClientOptions} of the current {@link Request}.
     */
    ClientOptions options();

    /**
     * Returns the fragment part of the URI of the current {@link Request}, as defined in
     * <a href="https://tools.ietf.org/html/rfc3986#section-3.5">the section 3.5 of RFC3986</a>.
     *
     * @return the fragment part of the request URI, or {@code null} if no fragment was specified
     */
    @Nullable
    String fragment();

    /**
     * Returns the amount of time allowed until the initial write attempt of the current {@link Request}
     * succeeds. This value is initially set from {@link ClientOption#DEFAULT_WRITE_TIMEOUT_MILLIS}.
     */
    long writeTimeoutMillis();

    /**
     * Returns the amount of time allowed until the initial write attempt of the current {@link Request}
     * succeeds. This value is initially set from {@link ClientOption#DEFAULT_WRITE_TIMEOUT_MILLIS}.
     */
    void setWriteTimeoutMillis(long writeTimeoutMillis);

    /**
     * Returns the amount of time allowed until the initial write attempt of the current {@link Request}
     * succeeds. This value is initially set from {@link ClientOption#DEFAULT_WRITE_TIMEOUT_MILLIS}.
     */
    void setWriteTimeout(Duration writeTimeout);

    /**
     * Returns the amount of time allowed until receiving the {@link Response} completely
     * since the transfer of the {@link Response} started. This value is initially set from
     * {@link ClientOption#DEFAULT_RESPONSE_TIMEOUT_MILLIS}.
     */
    long responseTimeoutMillis();

    /**
     * Sets the amount of time allowed until receiving the {@link Response} completely
     * since the transfer of the {@link Response} started. This value is initially set from
     * {@link ClientOption#DEFAULT_RESPONSE_TIMEOUT_MILLIS}.
     */
    void setResponseTimeoutMillis(long responseTimeoutMillis);

    /**
     * Sets the amount of time allowed until receiving the {@link Response} completely
     * since the transfer of the {@link Response} started. This value is initially set from
     * {@link ClientOption#DEFAULT_RESPONSE_TIMEOUT_MILLIS}.
     */
    void setResponseTimeout(Duration responseTimeout);

    /**
     * Returns the maximum length of the received {@link Response}.
     * This value is initially set from {@link ClientOption#DEFAULT_MAX_RESPONSE_LENGTH}.
     *
     * @see ContentTooLargeException
     */
    long maxResponseLength();

    /**
     * Sets the maximum length of the received {@link Response}.
     * This value is initially set from {@link ClientOption#DEFAULT_MAX_RESPONSE_LENGTH}.
     *
     * @see ContentTooLargeException
     */
    void setMaxResponseLength(long maxResponseLength);

    /**
     * Returns an immutable {@link HttpHeaders} which is included when a {@link Client} sends an
     * {@link HttpRequest}.
     */
    HttpHeaders additionalRequestHeaders();

    /**
     * Sets a header with the specified {@code name} and {@code value}. This will remove all previous values
     * associated with the specified {@code name}.
     * The header will be included when a {@link Client} sends an {@link HttpRequest}.
     */
    void setAdditionalRequestHeader(AsciiString name, String value);

    /**
     * Clears the current header and sets the specified {@link Headers} which is included when a
     * {@link Client} sends an {@link HttpRequest}.
     */
    void setAdditionalRequestHeaders(Headers<? extends AsciiString, ? extends String, ?> headers);

    /**
     * Adds a header with the specified {@code name} and {@code value}. The header will be included when
     * a {@link Client} sends an {@link HttpRequest}.
     */
    void addAdditionalRequestHeader(AsciiString name, String value);

    /**
     * Adds the specified {@link Headers} which is included when a {@link Client} sends an
     * {@link HttpRequest}.
     */
    void addAdditionalRequestHeaders(Headers<? extends AsciiString, ? extends String, ?> headers);

    /**
     * Removes all headers with the specified {@code name}.
     *
     * @return {@code true} if at least one entry has been removed
     */
    boolean removeAdditionalRequestHeader(AsciiString name);
}
