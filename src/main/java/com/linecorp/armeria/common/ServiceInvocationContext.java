/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.net.SocketAddress;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.ProgressivePromise;
import io.netty.util.concurrent.Promise;

/**
 * Provides information about an invocation and related utilities. Every remote invocation, regardless of if
 * it's client side or server side, has its own {@link ServiceInvocationContext} instance.
 */
public abstract class ServiceInvocationContext extends DefaultAttributeMap {

    private static final FastThreadLocal<ServiceInvocationContext> context = new FastThreadLocal<>();

    /**
     * Returns the context of the invocation that is being handled in the current thread.
     */
    public static Optional<ServiceInvocationContext> current() {
        return Optional.ofNullable(context.get());
    }

    /**
     * (Do not use; internal use only) Set the invocation context of the current thread.
     */
    public static void setCurrent(ServiceInvocationContext ctx) {
        context.set(requireNonNull(ctx, "ctx"));
    }

    /**
     * (Do not use; internal use only) Removes the invocation context from the current thread.
     */
    public static void removeCurrent() {
        context.remove();
    }

    private final Channel ch;
    private final Scheme scheme;
    private final String host;
    private final String path;
    private final String mappedPath;
    private final String loggerName;
    private final Object originalRequest;
    private Logger logger;
    private String strVal;

    /**
     * Creates a new instance.
     *
     * @param ch the {@link Channel} that handles the invocation
     * @param scheme the {@link Scheme} of the invocation
     * @param host the host part of the invocation, as defined in
     *             <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.23">the
     *             section 14.23 of RFC2616</a>
     * @param path the absolute path part of the invocation, as defined in
     *             <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec5.html#sec5.1.2">the
     *             section 5.1.2 of RFC2616</a>
     * @param mappedPath the path with its context path removed. Same with {@code path} if client side.
     * @param loggerName the name of the {@link Logger} which is returned by {@link #logger()}
     * @param originalRequest the session-level protocol message which triggered the invocation
     *                        e.g. {@link FullHttpRequest}
     */
    protected ServiceInvocationContext(
            Channel ch, Scheme scheme, String host, String path, String mappedPath,
            String loggerName, Object originalRequest) {

        this.ch = requireNonNull(ch, "ch");
        this.scheme = requireNonNull(scheme, "scheme");
        this.host = requireNonNull(host, "host");
        this.path = requireNonNull(path, "path");
        this.mappedPath = requireNonNull(mappedPath, "mappedPath");
        this.loggerName = requireNonNull(loggerName, "loggerName");
        this.originalRequest = originalRequest;
    }

    final Channel channel() {
        return ch;
    }

    /**
     * Returns the {@link EventLoop} that is handling this invocation.
     */
    public final EventLoop eventLoop() {
        return channel().eventLoop();
    }

    /**
     * Returns the {@link ByteBufAllocator} used by the connection that is handling this invocation. Use this
     * {@link ByteBufAllocator} when allocating a new {@link ByteBuf}, so that the same pool is used for buffer
     * allocation and thus the pool is utilized fully.
     */
    public final ByteBufAllocator alloc() {
        return channel().alloc();
    }

    /**
     * Returns the {@link Logger}  which logs information about this invocation as the prefix of log messages.
     * e.g. If a user called {@code ctx.logger().info("Hello")},
     * <pre>{@code
     * [id: 0x270781f4, /127.0.0.1:63466 => /127.0.0.1:63432][tbinary+h2c://example.com/path#method][42] Hello
     * }</pre>
     */
    public final Logger logger() {
        Logger logger = this.logger;
        if (logger == null) {
            this.logger = logger = new ServiceInvocationAwareLogger(this, LoggerFactory.getLogger(loggerName));
        }
        return logger;
    }

    /**
     * Returns the {@link Scheme} of this invocation.
     */
    public final Scheme scheme() {
        return scheme;
    }

    /**
     * Returns the host part of this invocation, as defined in
     * <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.23">the
     * section 14.23 of RFC2616</a>. e.g. {@code "example.com"} or {@code "example.com:8080"}
     */
    public final String host() {
        return host;
    }

    /**
     * Returns the absolute path part of this invocation, as defined in
     * <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec5.html#sec5.1.2">the
     * section 5.1.2 of RFC2616</a>.
     */
    public final String path() {
        return path;
    }

    /**
     * Returns the path with its context path removed. This method can be useful for a reusable service bound
     * at various path prefixes. For client side invocations, this method always returns the same value as
     * {@link #path()}.
     */
    public final String mappedPath() {
        return mappedPath;
    }

    /**
     * Returns the remote address of this invocation.
     */
    public final SocketAddress remoteAddress() {
        return ch.remoteAddress();
    }

    /**
     * Returns the ID of this invocation. Note that the ID returned by this method is only for debugging
     * purposes and thus is never guaranteed to be unique.
     */
    public abstract String invocationId();

    /**
     * Returns the method name of this invocation.
     */
    public abstract String method();

    /**
     * Returns the parameter types of this invocation. Note that this is potentially an expensive operation.
     */
    public abstract List<Class<?>> paramTypes();

    /**
     * Returns the return type of this invocation.
     */
    public abstract Class<?> returnType();

    /**
     * Returns the parameters of this invocation.
     */
    public abstract List<Object> params();

    /**
     * Returns the session-level protocol message which triggered this invocation. e.g. {@link FullHttpRequest}
     */
    @SuppressWarnings("unchecked")
    public <T> T originalRequest() {
        return (T) originalRequest;
    }

    /**
     * Returns a new {@link Promise} whose {@link FutureListener}s will be notified by the same
     * {@link #eventLoop()}.
     */
    public final <V> Promise<V> newPromise() {
        return eventLoop().newPromise();
    }

    /**
     * Returns a new {@link ProgressivePromise} whose {@link FutureListener}s will be notified by the same
     * {@link #eventLoop()}.
     */
    public final <V> ProgressivePromise<V> newProgressivePromise() {
        return eventLoop().newProgressivePromise();
    }

    /**
     * Returns a new {@link Future} which is marked as succeeded already and whose {@link FutureListener}s will
     * be notified by the same {@link #eventLoop()}. {@link Future#isSuccess()} will always return
     * {@code true}. All {@link FutureListener}s added to it will be notified immediately. The blocking
     * operations of the returned {@link Future} will return immediately without blocking as well.
     */
    public final <V> Future<V> newSucceededFuture(V result) {
        return eventLoop().newSucceededFuture(result);
    }

    /**
     * Returns a new {@link Future} which is marked as failed already and whose {@link FutureListener}s will
     * be notified by the same {@link #eventLoop()}. {@link Future#isSuccess()} will always return
     * {@code false}. All {@link FutureListener}s added to it will be notified immediately. The blocking
     * operations of the returned {@link Future} will return immediately without blocking as well.
     */
    public final <V> Future<V> newFailedFuture(Throwable cause) {
        return eventLoop().newFailedFuture(cause);
    }

    @Override
    public final String toString() {
        String strVal = this.strVal;
        if (strVal == null) {
            final StringBuilder buf = new StringBuilder(64);

            buf.append(channel());
            buf.append('[');
            buf.append(scheme().uriText());
            buf.append("://");
            buf.append(host());
            buf.append(path());
            buf.append('#');
            buf.append(method());
            buf.append("][");
            buf.append(invocationId());
            buf.append(']');

            this.strVal = strVal = buf.toString();
        }

        return strVal;
    }
}
