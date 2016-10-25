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
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
public class DefaultServiceRequestContext extends NonWrappingRequestContext implements ServiceRequestContext {

    private final Channel ch;
    private final ServiceConfig cfg;
    private final String mappedPath;
    private final SSLSession sslSession;

    private final DefaultRequestLog requestLog;
    private final DefaultResponseLog responseLog;
    private final Logger logger;

    private ExecutorService blockingTaskExecutor;

    private long requestTimeoutMillis;
    private long maxRequestLength;
    private volatile RequestTimeoutChangeListener requestTimeoutChangeListener;

    private String strVal;

    /**
     * Creates a new instance.
     *
     * @param ch the {@link Channel} that handles the invocation
     * @param sessionProtocol the {@link SessionProtocol} of the invocation
     * @param request the request associated with this context
     * @param sslSession the {@link SSLSession} for this invocation if it is over TLS
     */
    public DefaultServiceRequestContext(
            ServiceConfig cfg, Channel ch, SessionProtocol sessionProtocol,
            String method, String path, String mappedPath, Object request,
            @Nullable SSLSession sslSession) {

        super(sessionProtocol, method, path, request);

        this.ch = ch;
        this.cfg = cfg;
        this.mappedPath = mappedPath;
        this.sslSession = sslSession;

        requestLog = new DefaultRequestLog();
        requestLog.start(ch, sessionProtocol, cfg.virtualHost().defaultHostname(), method, path);
        responseLog = new DefaultResponseLog(requestLog, requestLog);
        logger = newLogger(cfg);

        final ServerConfig serverCfg = cfg.server().config();
        requestTimeoutMillis = serverCfg.defaultRequestTimeoutMillis();
        maxRequestLength = serverCfg.defaultMaxRequestLength();
    }

    private RequestContextAwareLogger newLogger(ServiceConfig cfg) {
        String loggerName = cfg.loggerName().orElse(null);
        if (loggerName == null) {
            loggerName = cfg.pathMapping().loggerName();
        }

        return new RequestContextAwareLogger(this, LoggerFactory.getLogger(
                cfg.server().config().serviceLoggerPrefix() + '.' + loggerName));
    }

    @Override
    protected Channel channel() {
        return ch;
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
    public PathMapping pathMapping() {
        return cfg.pathMapping();
    }

    @Override
    public <T extends Service<?, ?>> T service() {
        return cfg.service();
    }

    @Override
    public ExecutorService blockingTaskExecutor() {
        if (blockingTaskExecutor != null) {
            return blockingTaskExecutor;
        }

        return blockingTaskExecutor = makeContextAware(server().config().blockingTaskExecutor());
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

    @Nullable
    @Override
    public SSLSession sslSession() {
        return sslSession;
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
        if (this.requestTimeoutMillis != requestTimeoutMillis) {
            this.requestTimeoutMillis = requestTimeoutMillis;
            final RequestTimeoutChangeListener listener = requestTimeoutChangeListener;
            if (listener != null) {
                if (ch.eventLoop().inEventLoop()) {
                    listener.onRequestTimeoutChange(requestTimeoutMillis);
                } else {
                    ch.eventLoop().execute(() -> listener.onRequestTimeoutChange(requestTimeoutMillis));
                }
            }
        }
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

    /**
     * Sets the listener that is notified when the {@linkplain #requestTimeoutMillis()} request timeout} of
     * the request is changed.
     *
     * <p>Note: This method is meant for internal use by server-side protocol implementation to reschedule
     * a timeout task when a user updates the request timeout configuration.
     */
    public void setRequestTimeoutChangeListener(RequestTimeoutChangeListener listener) {
        requireNonNull(listener, "listener");
        if (requestTimeoutChangeListener != null) {
            throw new IllegalStateException("requestTimeoutChangeListener is set already.");
        }
        requestTimeoutChangeListener = listener;
    }

    @Override
    public String toString() {
        String strVal = this.strVal;
        if (strVal != null) {
            return strVal;
        }

        final StringBuilder buf = new StringBuilder(96);

        // Prepend the current channel information if available.
        final Channel ch = channel();
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
