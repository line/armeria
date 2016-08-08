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

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.NonWrappingRequestContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.DefaultHttpHeaders;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.logging.DefaultRequestLog;
import com.linecorp.armeria.common.logging.DefaultResponseLog;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.ResponseLog;
import com.linecorp.armeria.common.logging.ResponseLogBuilder;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;

/**
 * Default {@link ClientRequestContext} implementation.
 */
public final class DefaultClientRequestContext extends NonWrappingRequestContext
        implements ClientRequestContext {

    private final EventLoop eventLoop;
    private final ClientOptions options;
    private final Endpoint endpoint;

    private final DefaultRequestLog requestLog;
    private final DefaultResponseLog responseLog;

    private long writeTimeoutMillis;
    private long responseTimeoutMillis;
    private long maxResponseLength;

    private String strVal;

    /**
     * Creates a new instance.
     *
     * @param sessionProtocol the {@link SessionProtocol} of the invocation
     * @param request the request associated with this context
     */
    public DefaultClientRequestContext(
            EventLoop eventLoop, SessionProtocol sessionProtocol,
            Endpoint endpoint, String method, String path, ClientOptions options, Object request) {

        super(sessionProtocol, method, path, request);

        this.eventLoop = eventLoop;
        this.options = options;
        this.endpoint = endpoint;

        requestLog = new DefaultRequestLog();
        responseLog = new DefaultResponseLog(requestLog);

        writeTimeoutMillis = options.defaultWriteTimeoutMillis();
        responseTimeoutMillis = options.defaultResponseTimeoutMillis();
        maxResponseLength = options.defaultMaxResponseLength();

        if (SessionProtocol.ofHttp().contains(sessionProtocol)) {
            final HttpHeaders headers = options.getOrElse(ClientOption.HTTP_HEADERS, HttpHeaders.EMPTY_HEADERS);
            if (!headers.isEmpty()) {
                final HttpHeaders headersCopy = new DefaultHttpHeaders(true, headers.size());
                headersCopy.set(headers);
                attr(HTTP_HEADERS).set(headersCopy);
            }
        }
    }

    @Override
    public EventLoop eventLoop() {
        return eventLoop;
    }

    @Override
    public ClientOptions options() {
        return options;
    }

    @Override
    public Endpoint endpoint() {
        return endpoint;
    }

    @Override
    public long writeTimeoutMillis() {
        return writeTimeoutMillis;
    }

    @Override
    public void setWriteTimeoutMillis(long writeTimeoutMillis) {
        if (writeTimeoutMillis < 0) {
            throw new IllegalArgumentException(
                    "writeTimeoutMillis: " + writeTimeoutMillis + " (expected: >= 0)");
        }
        this.writeTimeoutMillis = writeTimeoutMillis;
    }

    @Override
    public void setWriteTimeout(Duration writeTimeout) {
        setWriteTimeoutMillis(requireNonNull(writeTimeout, "writeTimeout").toMillis());
    }

    @Override
    public long responseTimeoutMillis() {
        return responseTimeoutMillis;
    }

    @Override
    public void setResponseTimeoutMillis(long responseTimeoutMillis) {
        if (responseTimeoutMillis < 0) {
            throw new IllegalArgumentException(
                    "responseTimeoutMillis: " + responseTimeoutMillis + " (expected: >= 0)");
        }
        this.responseTimeoutMillis = responseTimeoutMillis;
    }

    @Override
    public void setResponseTimeout(Duration responseTimeout) {
        setResponseTimeoutMillis(requireNonNull(responseTimeout, "responseTimeout").toMillis());
    }

    @Override
    public long maxResponseLength() {
        return maxResponseLength;
    }

    @Override
    public void setMaxResponseLength(long maxResponseLength) {
        this.maxResponseLength = maxResponseLength;
    }

    @Override
    public RequestLogBuilder requestLogBuilder() {
        return requestLog;
    }

    @Override
    public ResponseLogBuilder responseLogBuilder() {
        return responseLog;
    }

    @Override
    public CompletableFuture<RequestLog> requestLogFuture() {
        return requestLog;
    }

    @Override
    public CompletableFuture<ResponseLog> responseLogFuture() {
        return responseLog;
    }

    @Override
    public String toString() {
        String strVal = this.strVal;
        if (strVal != null) {
            return strVal;
        }

        final StringBuilder buf = new StringBuilder(96);

        // Prepend the current channel information if available.
        final Channel ch = requestLog.channel();
        final boolean hasChannel = ch != null;
        if (hasChannel) {
            buf.append(ch);
        }

        buf.append('[')
           .append(sessionProtocol().uriText())
           .append("://")
           .append(endpoint.authority())
           .append(path())
           .append('#')
           .append(method())
           .append(']');

        strVal = buf.toString();

        if (hasChannel) {
            this.strVal = strVal;
        }

        return strVal;
    }
}
