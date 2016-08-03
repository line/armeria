/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client;

import java.time.Duration;

import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.server.ServerConfig;

import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

/**
 * Provides information about a {@link Request}, its {@link Response} and its related utilities.
 * Every client request has its own {@link ClientRequestContext} instance.
 */
public interface ClientRequestContext extends RequestContext {

    /**
     * The {@link AttributeKey} of the {@link HttpHeaders} to include when a {@link Client} sends an
     * {@link HttpRequest}. This {@link Attribute} is initially populated from
     * {@link ClientOption#HTTP_HEADERS} and can be modified by a {@link DecoratingClient}.
     */
    AttributeKey<HttpHeaders> HTTP_HEADERS = AttributeKey.valueOf(ClientRequestContext.class, "HTTP_HEADERS");

    /**
     * Returns the remote {@link Endpoint} of the current {@link Request}.
     */
    Endpoint endpoint();

    /**
     * Returns the {@link ClientOptions} of the current {@link Request}.
     */
    ClientOptions options();

    /**
     * Returns the amount of time allowed until sending out the current {@link Request} completely.
     * This value is initially set from {@link ClientOption#DEFAULT_WRITE_TIMEOUT_MILLIS}.
     */
    long writeTimeoutMillis();

    /**
     * Sets the amount of time allowed until sending out the current {@link Request} completely.
     * This value is initially set from {@link ClientOption#DEFAULT_WRITE_TIMEOUT_MILLIS}.
     */
    void setWriteTimeoutMillis(long writeTimeoutMillis);

    /**
     * Sets the amount of time allowed until sending out the current {@link Request} completely.
     * This value is initially set from {@link ClientOption#DEFAULT_WRITE_TIMEOUT_MILLIS}.
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
}
