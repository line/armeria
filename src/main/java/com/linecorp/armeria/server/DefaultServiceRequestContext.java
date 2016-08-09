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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;

import com.linecorp.armeria.common.NonWrappingRequestContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.DefaultRequestLog;
import com.linecorp.armeria.common.logging.DefaultResponseLog;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.ResponseLog;
import com.linecorp.armeria.common.logging.ResponseLogBuilder;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;

/**
 * Default {@link ServiceRequestContext} implementation.
 */
public final class DefaultServiceRequestContext extends NonWrappingRequestContext
        implements ServiceRequestContext {

    private final Channel ch;
    private final ServiceConfig cfg;
    private final String mappedPath;
    private final Logger logger;

    private final DefaultRequestLog requestLog;
    private final DefaultResponseLog responseLog;

    private long requestTimeoutMillis;
    private long maxRequestLength;

    private String strVal;

    /**
     * Creates a new instance.
     *
     * @param ch the {@link Channel} that handles the invocation
     * @param sessionProtocol the {@link SessionProtocol} of the invocation
     * @param logger the {@link Logger} for the invocation
     * @param request the request associated with this context
     */
    public DefaultServiceRequestContext(
            ServiceConfig cfg, Channel ch, SessionProtocol sessionProtocol,
            String method, String path, String mappedPath, Logger logger, Object request) {

        super(sessionProtocol, method, path, request);

        this.ch = ch;
        this.cfg = cfg;
        this.mappedPath = mappedPath;
        this.logger = new RequestContextAwareLogger(this, logger);

        requestLog = new DefaultRequestLog();
        requestLog.start(ch, sessionProtocol, cfg.virtualHost().defaultHostname(), method, path);
        responseLog = new DefaultResponseLog(requestLog);

        final ServerConfig serverCfg = cfg.server().config();
        requestTimeoutMillis = serverCfg.defaultRequestTimeoutMillis();
        maxRequestLength = serverCfg.defaultMaxRequestLength();
    }

    @Override
    public Server server() {
        return cfg.server();
    }

    @Override
    public VirtualHost virtualHost() {
        return cfg.virtualHost();
    }

    @Override
    public <T extends Service<?, ?>> T service() {
        return cfg.service();
    }

    @Override
    public ExecutorService blockingTaskExecutor() {
        return server().config().blockingTaskExecutor();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends SocketAddress> A remoteAddress() {
        return (A) ch.remoteAddress();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends SocketAddress> A localAddress() {
        return (A) ch.localAddress();
    }

    @Override
    public EventLoop eventLoop() {
        return ch.eventLoop();
    }

    @Override
    public String mappedPath() {
        return mappedPath;
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public long requestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    @Override
    public void setRequestTimeoutMillis(long requestTimeoutMillis) {
        if (requestTimeoutMillis < 0) {
            throw new IllegalArgumentException(
                    "requestTimeoutMillis: " + requestTimeoutMillis + " (expected: >= 0)");
        }
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    @Override
    public void setRequestTimeout(Duration requestTimeout) {
        setRequestTimeoutMillis(requireNonNull(requestTimeout, "requestTimeout").toMillis());
    }

    @Override
    public long maxRequestLength() {
        return maxRequestLength;
    }

    @Override
    public void setMaxRequestLength(long maxRequestLength) {
        if (maxRequestLength < 0) {
            throw new IllegalArgumentException(
                    "maxRequestLength: " + maxRequestLength + " (expected: >= 0)");
        }
        this.maxRequestLength = maxRequestLength;
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
           .append(virtualHost().defaultHostname())
           .append(':')
           .append(((InetSocketAddress) remoteAddress()).getPort())
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
