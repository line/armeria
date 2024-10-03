/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.common;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.concurrent.ThreadLocalRandom;

import javax.net.ssl.SSLSession;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContextBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.armeria.internal.common.DefaultRequestTarget;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContextBuilder;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelProgressivePromise;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.EventLoop;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.NetUtil;

/**
 * Provides the information required for building a {@link RequestContext}.
 *
 * @see ServiceRequestContextBuilder
 * @see ClientRequestContextBuilder
 */
public abstract class AbstractRequestContextBuilder {

    private static final String FALLBACK_AUTHORITY = "127.0.0.1";

    private final boolean server;
    @Nullable
    private final HttpRequest req;
    @Nullable
    private final RpcRequest rpcReq;
    private SessionProtocol sessionProtocol;
    @Nullable
    private RequestId id;
    private HttpMethod method;
    private final String authority;
    private final RequestTarget reqTarget;

    private MeterRegistry meterRegistry = NoopMeterRegistry.get();
    @Nullable
    private EventLoop eventLoop;
    private ByteBufAllocator alloc = ByteBufAllocator.DEFAULT;
    @Nullable
    private InetSocketAddress remoteAddress;
    @Nullable
    private InetSocketAddress localAddress;
    @Nullable
    private SSLSession sslSession;
    private boolean requestStartTimeSet;
    private long requestStartTimeNanos;
    private long requestStartTimeMicros;
    @Nullable
    private Channel channel;
    private boolean timedOut;

    /**
     * Creates a new builder with the specified {@link HttpRequest}.
     *
     * @param server whether this builder will build a server-side context.
     * @param req the {@link HttpRequest}.
     */
    protected AbstractRequestContextBuilder(boolean server, HttpRequest req) {
        requireNonNull(req, "req");
        this.server = server;
        rpcReq = null;
        sessionProtocol = SessionProtocol.H2C;

        method = req.headers().method();
        authority = firstNonNull(req.headers().authority(), FALLBACK_AUTHORITY);

        final String rawPath = req.headers().path();
        final RequestTarget reqTarget = server ? RequestTarget.forServer(rawPath)
                                               : RequestTarget.forClient(rawPath);
        checkArgument(reqTarget != null, "request.path is not valid: %s", rawPath);
        checkArgument(reqTarget.form() != RequestTargetForm.ABSOLUTE,
                      "request.path must not contain scheme or authority: %s", rawPath);

        final String newRawPath = reqTarget.pathAndQuery();
        if (newRawPath.equals(rawPath)) {
            this.req = req;
        } else {
            this.req = req.withHeaders(req.headers()
                                          .toBuilder()
                                          .path(newRawPath));
        }

        this.reqTarget = reqTarget;
    }

    /**
     * Creates a new builder with the specified {@link RpcRequest} and {@link URI}.
     *
     * @param server whether this builder will build a server-side context.
     * @param rpcReq the {@link RpcRequest}.
     * @param uri the {@link URI} of the request endpoint.
     */
    protected AbstractRequestContextBuilder(boolean server, RpcRequest rpcReq, URI uri) {
        this.server = server;
        req = null;
        this.rpcReq = requireNonNull(rpcReq, "rpcReq");
        method = HttpMethod.POST;

        requireNonNull(uri, "uri");
        authority = firstNonNull(uri.getRawAuthority(), FALLBACK_AUTHORITY);
        sessionProtocol = getSessionProtocol(uri);

        if (server) {
            String path = uri.getRawPath();
            final String query = uri.getRawQuery();
            if (query != null) {
                path += '?' + query;
            }
            final RequestTarget reqTarget = RequestTarget.forServer(path);
            if (reqTarget == null) {
                throw new IllegalArgumentException("invalid uri: " + uri);
            }
            this.reqTarget = reqTarget;
        } else {
            reqTarget = DefaultRequestTarget.createWithoutValidation(
                    RequestTargetForm.ORIGIN, null, null, null, -1,
                    uri.getRawPath(), uri.getRawPath(), uri.getRawQuery(), uri.getRawFragment());
        }
    }

    private static SessionProtocol getSessionProtocol(URI uri) {
        final String schemeStr = uri.getScheme();
        if (schemeStr != null && schemeStr.indexOf('+') < 0) {
            final SessionProtocol parsed = SessionProtocol.find(schemeStr);
            if (parsed == null) {
                throw newInvalidSchemeException(uri);
            }
            return parsed;
        } else {
            final Scheme parsed = Scheme.tryParse(schemeStr);
            if (parsed == null) {
                throw newInvalidSchemeException(uri);
            }
            return parsed.sessionProtocol();
        }
    }

    private static IllegalArgumentException newInvalidSchemeException(URI uri) {
        return new IllegalArgumentException("uri.scheme is not valid: " + uri);
    }

    /**
     * Returns the {@link MeterRegistry}.
     */
    protected final MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    /**
     * Sets the {@link MeterRegistry}. If not set, {@link NoopMeterRegistry} is used.
     */
    public AbstractRequestContextBuilder meterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
        return this;
    }

    /**
     * Returns the {@link EventLoop} that handles the request.
     */
    @Nullable
    protected final EventLoop eventLoop() {
        return eventLoop;
    }

    /**
     * Sets the {@link EventLoop} that handles the request.
     * If not set, one of the {@link CommonPools#workerGroup()} is used.
     */
    public AbstractRequestContextBuilder eventLoop(EventLoop eventLoop) {
        this.eventLoop = requireNonNull(eventLoop, "eventLoop");
        return this;
    }

    /**
     * Returns the {@link ByteBufAllocator}.
     */
    protected final ByteBufAllocator alloc() {
        return alloc;
    }

    /**
     * Sets the {@link ByteBufAllocator}. If not set, {@link ByteBufAllocator#DEFAULT} is used.
     */
    public AbstractRequestContextBuilder alloc(ByteBufAllocator alloc) {
        this.alloc = requireNonNull(alloc, "alloc");
        return this;
    }

    /**
     * Returns the {@link HttpRequest} of the context.
     */
    @Nullable
    protected final HttpRequest request() {
        return req;
    }

    /**
     * Returns the {@link RpcRequest} of the context.
     */
    @Nullable
    protected final RpcRequest rpcRequest() {
        return rpcReq;
    }

    /**
     * Returns the {@link SessionProtocol} of the request.
     */
    protected final SessionProtocol sessionProtocol() {
        return sessionProtocol;
    }

    /**
     * Sets the {@link SessionProtocol} of the request.
     *
     * @throws IllegalArgumentException if the specified {@link SessionProtocol} is not compatible with
     *                                  the scheme of the {@link URI} you specified when creating this builder.
     *                                  For example, you cannot specify {@link SessionProtocol#H2C} if you
     *                                  created this builder with {@code h1c://example.com/}.
     */
    public AbstractRequestContextBuilder sessionProtocol(SessionProtocol sessionProtocol) {
        requireNonNull(sessionProtocol, "sessionProtocol");
        if (rpcReq != null) {
            checkArgument(sessionProtocol == this.sessionProtocol,
                          "sessionProtocol: %s (expected: same as the session protocol specified in 'uri')",
                          sessionProtocol);
        } else {
            this.sessionProtocol = sessionProtocol;
        }
        return this;
    }

    /**
     * Returns the remote socket address of the connection.
     */
    protected final InetSocketAddress remoteAddress() {
        if (remoteAddress == null) {
            if (server) {
                remoteAddress = new InetSocketAddress(NetUtil.LOCALHOST, randomClientPort());
            } else {
                remoteAddress = new InetSocketAddress(NetUtil.LOCALHOST,
                                                      guessServerPort(sessionProtocol, authority));
            }
        }
        return remoteAddress;
    }

    /**
     * Sets the remote socket address of the connection. If not set, it is auto-generated with the localhost
     * IP address (e.g. {@code "127.0.0.1"} or {@code "::1"}).
     */
    public AbstractRequestContextBuilder remoteAddress(InetSocketAddress remoteAddress) {
        this.remoteAddress = requireNonNull(remoteAddress, "remoteAddress");
        return this;
    }

    /**
     * Returns the local socket address of the connection.
     */
    protected final InetSocketAddress localAddress() {
        if (localAddress == null) {
            if (server) {
                localAddress = new InetSocketAddress(NetUtil.LOCALHOST,
                                                     guessServerPort(sessionProtocol, authority));
            } else {
                localAddress = new InetSocketAddress(NetUtil.LOCALHOST, randomClientPort());
            }
        }
        return localAddress;
    }

    /**
     * Sets the local socket address of the connection. If not set, it is auto-generated with the localhost
     * IP address (e.g. {@code "127.0.0.1"} or {@code "::1"}).
     */
    public AbstractRequestContextBuilder localAddress(InetSocketAddress localAddress) {
        this.localAddress = requireNonNull(localAddress, "localAddress");
        return this;
    }

    private static int guessServerPort(SessionProtocol sessionProtocol, @Nullable String authority) {
        if (authority == null) {
            return sessionProtocol.defaultPort();
        }

        final int lastColonPos = authority.lastIndexOf(':');
        if (lastColonPos < 0) {
            return sessionProtocol.defaultPort();
        }

        final int port;
        try {
            port = Integer.parseInt(authority.substring(lastColonPos + 1));
        } catch (NumberFormatException e) {
            return sessionProtocol.defaultPort();
        }

        if (port <= 0 || port >= 65536) {
            return sessionProtocol.defaultPort();
        }

        return port;
    }

    private static int randomClientPort() {
        return ThreadLocalRandom.current().nextInt(32768, 65536);
    }

    /**
     * Returns the {@link SSLSession} of the connection.
     *
     * @return the {@link SSLSession}, or {@code null} if the {@link SessionProtocol} is not TLS.
     */
    @Nullable
    protected final SSLSession sslSession() {
        checkState(!sessionProtocol.isTls() || sslSession != null,
                   "sslSession must be set for a TLS-enabled protocol: %s", sessionProtocol);
        return sessionProtocol.isTls() ? sslSession : null;
    }

    /**
     * Sets the {@link SSLSession} of the connection. If the current {@link SessionProtocol} is not TLS,
     * the TLS version of the current {@link SessionProtocol} will be set automatically. For example,
     * {@link SessionProtocol#H2C} will be automatically upgraded to {@link SessionProtocol#H2}.
     * Note that upgrading the current {@link SessionProtocol} may trigger an {@link IllegalArgumentException},
     * as described in {@link #sessionProtocol(SessionProtocol)}.
     */
    public AbstractRequestContextBuilder sslSession(SSLSession sslSession) {
        this.sslSession = requireNonNull(sslSession, "sslSession");
        switch (sessionProtocol) {
            case HTTP:
                sessionProtocol(SessionProtocol.HTTPS);
                break;
            case H1C:
                sessionProtocol(SessionProtocol.H1);
                break;
            case H2C:
                sessionProtocol(SessionProtocol.H2);
                break;
        }
        return this;
    }

    /**
     * Returns whether the request start time has been specified. If not specified, the builder will use
     * the current time returned by {@link #requestStartTimeNanos()} and {@link #requestStartTimeMicros()}
     * as the request start time.
     */
    protected final boolean isRequestStartTimeSet() {
        return requestStartTimeSet;
    }

    /**
     * Returns the {@link System#nanoTime()} value when the request started.
     *
     * @throws IllegalStateException if the request start time is unspecified.
     */
    protected final long requestStartTimeNanos() {
        checkState(isRequestStartTimeSet(), "requestStartTime is not set.");
        return requestStartTimeNanos;
    }

    /**
     * Returns the number of microseconds since the epoch when the request started.
     *
     * @throws IllegalStateException if the request start time is unspecified.
     */
    protected final long requestStartTimeMicros() {
        checkState(isRequestStartTimeSet(), "requestStartTime is not set.");
        return requestStartTimeMicros;
    }

    /**
     * Sets the request start time of the request.
     *
     * @param requestStartTimeNanos the {@link System#nanoTime()} value when the request started.
     * @param requestStartTimeMicros the number of microseconds since the epoch when the request started.
     */
    public AbstractRequestContextBuilder requestStartTime(long requestStartTimeNanos,
                                                          long requestStartTimeMicros) {
        this.requestStartTimeNanos = requestStartTimeNanos;
        this.requestStartTimeMicros = requestStartTimeMicros;
        requestStartTimeSet = true;
        return this;
    }

    /**
     * Returns the {@link HttpMethod} of the request.
     */
    protected final HttpMethod method() {
        return method;
    }

    /**
     * Sets the {@link HttpMethod} of the request.
     *
     * @throws IllegalArgumentException if the specified {@link HttpMethod} is not same with the
     *                                  {@link HttpMethod} of the {@link HttpRequest} you specified when
     *                                  creating this builder. This exception is not thrown if you
     *                                  created a builder with an {@link RpcRequest}.
     */
    protected AbstractRequestContextBuilder method(HttpMethod method) {
        requireNonNull(method, "method");
        if (req != null) {
            checkArgument(method == req.method(),
                          "method: %s (expected: same as request.method)", method);
        } else {
            this.method = method;
        }
        return this;
    }

    /**
     * Returns the authority of the request.
     */
    protected final String authority() {
        return authority;
    }

    /**
     * Returns the {@link RequestTarget}.
     */
    protected final RequestTarget requestTarget() {
        return reqTarget;
    }

    /**
     * Sets the {@link RequestId}.
     * If not set, a random {@link RequestId} is generated with {@link RequestId#random()}.
     */
    public AbstractRequestContextBuilder id(RequestId id) {
        this.id = requireNonNull(id, "id");
        return this;
    }

    /**
     * Returns the {@link RequestId}.
     */
    protected final RequestId id() {
        if (id == null) {
            id = RequestId.random();
        }
        return id;
    }

    /**
     * Returns a fake {@link Channel} which is required internally when creating a context.
     */
    protected final Channel fakeChannel(EventLoop eventLoop) {
        if (channel == null) {
            channel = new FakeChannel(eventLoop, alloc(), remoteAddress(), localAddress());
        }
        return channel;
    }

    /**
     * Returns whether a timeout is set.
     */
    protected final boolean timedOut() {
        return timedOut;
    }

    /**
     * Sets the specified {@code timedOut}. If the specified {@code timedOut} is {@code true},
     * {@link RequestContext#isTimedOut()} will always return {@code true}.
     * This is useful for checking the behavior of a {@link Service} and {@link Client}
     * when a request exceeds a deadline.
     */
    public AbstractRequestContextBuilder timedOut(boolean timedOut) {
        this.timedOut = timedOut;
        return this;
    }

    @SuppressWarnings("ComparableImplementedButEqualsNotOverridden")
    private static final class FakeChannel implements Channel {

        private final ChannelId id = DefaultChannelId.newInstance();
        private final EventLoop eventLoop;
        private final ByteBufAllocator alloc;
        private final SocketAddress remoteAddress;
        private final SocketAddress localAddress;

        FakeChannel(EventLoop eventLoop, ByteBufAllocator alloc,
                    SocketAddress remoteAddress, SocketAddress localAddress) {
            this.eventLoop = eventLoop;
            this.alloc = alloc;
            this.remoteAddress = remoteAddress;
            this.localAddress = localAddress;
        }

        @Override
        public ChannelId id() {
            return id;
        }

        @Override
        public EventLoop eventLoop() {
            return eventLoop;
        }

        @Nullable
        @Override
        public Channel parent() {
            return null;
        }

        @Override
        public ChannelConfig config() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isOpen() {
            return false;
        }

        @Override
        public boolean isRegistered() {
            return false;
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public ChannelMetadata metadata() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SocketAddress localAddress() {
            return localAddress;
        }

        @Override
        public SocketAddress remoteAddress() {
            return remoteAddress;
        }

        @Override
        public ChannelFuture closeFuture() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWritable() {
            return false;
        }

        @Override
        public long bytesBeforeUnwritable() {
            return 0;
        }

        @Override
        public long bytesBeforeWritable() {
            return Long.MAX_VALUE;
        }

        @Override
        public Unsafe unsafe() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelPipeline pipeline() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ByteBufAllocator alloc() {
            return alloc;
        }

        @Override
        public Channel read() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Channel flush() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelFuture bind(SocketAddress localAddress) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelFuture connect(SocketAddress remoteAddress) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress,
                                     ChannelPromise promise) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelFuture disconnect() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelFuture disconnect(ChannelPromise promise) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelFuture close() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelFuture close(ChannelPromise promise) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelFuture deregister() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelFuture deregister(ChannelPromise promise) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelFuture write(Object msg) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelFuture write(Object msg, ChannelPromise promise) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelFuture writeAndFlush(Object msg) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelPromise newPromise() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelProgressivePromise newProgressivePromise() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelFuture newSucceededFuture() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelFuture newFailedFuture(Throwable cause) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelPromise voidPromise() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Attribute<T> attr(AttributeKey<T> key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> boolean hasAttr(AttributeKey<T> key) {
            return false;
        }

        @Override
        public int compareTo(Channel o) {
            return id().compareTo(o.id());
        }

        @Override
        public String toString() {
            return "[id: 0x" + id.asShortText() + ", L:" + localAddress + " - " + "R:" + remoteAddress + ']';
        }
    }
}
