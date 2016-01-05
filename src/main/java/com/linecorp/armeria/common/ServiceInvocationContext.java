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
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

/**
 * Provides information about an invocation and related utilities. Every remote invocation, regardless of if
 * it's client side or server side, has its own {@link ServiceInvocationContext} instance.
 */
public abstract class ServiceInvocationContext extends DefaultAttributeMap {

    private static final FastThreadLocal<ServiceInvocationContext> context = new FastThreadLocal<>();

    /**
     * Returns the context of the invocation that is being handled in the current thread.
     *
     * @throws IllegalStateException if the context is unavailable in the current thread
     */
    public static ServiceInvocationContext current() {
        final ServiceInvocationContext ctx = context.get();
        if (ctx == null) {
            throw new IllegalStateException(ServiceInvocationContext.class.getSimpleName() + " unavailable");
        }
        return ctx;
    }

    /**
     * Maps the context of the invocation that is being handled in the current thread.
     *
     * @param mapper the {@link Function} that maps the invocation
     * @param defaultValueSupplier the {@link Supplier} that provides the value when the context is unavailable
     *                             in the current thread. If {@code null}, the {@code null} will be returned
     *                             when the context is unavailable in the current thread.
     */
    public static <T> T mapCurrent(
            Function<? super ServiceInvocationContext, T> mapper, @Nullable Supplier<T> defaultValueSupplier) {

        final ServiceInvocationContext ctx = context.get();
        if (ctx != null) {
            return mapper.apply(ctx);
        }

        if (defaultValueSupplier != null) {
            return defaultValueSupplier.get();
        }

        return null;
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

    Channel channel() {
        return ch;
    }

    /**
     * Returns the {@link EventLoop} that is handling this invocation.
     */
    public final EventLoop eventLoop() {
        return channel().eventLoop();
    }

    /**
     * Returns an {@link EventLoop} that will make sure this invocation is set
     * as the current invocation before executing any callback. This should
     * almost always be used for executing asynchronous callbacks in service
     * code to make sure features that require the invocation context work
     * properly. Most asynchronous libraries like
     * {@link java.util.concurrent.CompletableFuture} provide methods that
     * accept an {@link Executor} to run callbacks on.
     */
    public final EventLoop contextAwareEventLoop() {
        return new ServiceInvocationContextAwareEventLoop(this, eventLoop());
    }

    /**
     * Returns an {@link Executor} that will execute callbacks in the given
     * {@code executor}, making sure to propagate the current invocation context
     * into the callback execution. It is generally preferred to use
     * {@link #contextAwareEventLoop()} to ensure the callback stays on the
     * same thread as well.
     */
    public final Executor makeContextAware(Executor executor) {
        return runnable -> executor.execute(makeContextAware(runnable));
    }

    /**
     * Returns a {@link Callable} that makes sure the current invocation context
     * is set and then invokes the input {@code callable}.
     */
    public final <T> Callable<T> makeContextAware(Callable<T> callable) {
        ServiceInvocationContext propagatedContext = this;
        return () -> {
            boolean mustResetContext = propagateContextIfNotPresent(propagatedContext);
            try {
                return callable.call();
            } finally {
                if (mustResetContext) {
                    removeCurrent();
                }
            }
        };
    }

    /**
     * Returns a {@link Runnable} that makes sure the current invocation context
     * is set and then invokes the input {@code runnable}.
     */
    public final Runnable makeContextAware(Runnable runnable) {
        ServiceInvocationContext propagatedContext = this;
        return () -> {
            boolean mustResetContext = propagateContextIfNotPresent(propagatedContext);
            try {
                runnable.run();
            } finally {
                if (mustResetContext) {
                    removeCurrent();
                }
            }
        };
    }

    /**
     * Returns a {@link FutureListener} that makes sure the current invocation
     * context is set and then invokes the input {@code listener}.
     */
    public final <T> FutureListener<T> makeContextAware(FutureListener<T> listener) {
        ServiceInvocationContext propagatedContext = this;
        return future -> {
            boolean mustResetContext = propagateContextIfNotPresent(propagatedContext);
            try {
                listener.operationComplete(future);
            } finally {
                if (mustResetContext) {
                    removeCurrent();
                }
            }
        };
    }

    /**
     * Returns a {@link ChannelFutureListener} that makes sure the current invocation
     * context is set and then invokes the input {@code listener}.
     */
    public final ChannelFutureListener makeContextAware(ChannelFutureListener listener) {
        ServiceInvocationContext propagatedContext = this;
        return future -> {
            boolean mustResetContext = propagateContextIfNotPresent(propagatedContext);
            try {
                listener.operationComplete(future);
            } finally {
                if (mustResetContext) {
                    removeCurrent();
                }
            }
        };
    }

    /**
     * Returns a {@link ChannelFutureListener} that makes sure the current invocation
     * context is set and then invokes the input {@code listener}.
     */
    final <T extends Future<?>> GenericFutureListener<T> makeContextAware(GenericFutureListener<T> listener) {
        ServiceInvocationContext propagatedContext = this;
        return future -> {
            boolean mustResetContext = propagateContextIfNotPresent(propagatedContext);
            try {
                listener.operationComplete(future);
            } finally {
                if (mustResetContext) {
                    removeCurrent();
                }
            }
        };
    }

    private static boolean propagateContextIfNotPresent(ServiceInvocationContext propagatedContext) {
        return mapCurrent(currentContext -> {
            if (!currentContext.equals(propagatedContext)) {
                throw new IllegalStateException(
                        "Trying to call object made with makeContextAware or object on executor made with " +
                        "makeContextAware with context " + propagatedContext +
                        ", but context is currently set to " + currentContext + ". This means the " +
                        "callback was passed from one invocation to another which is not allowed. Make " +
                        "sure you are not saving callbacks into shared state.");
            }
            return false;
        }, () -> {
            setCurrent(propagatedContext);
            return true;
        });
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
     * Resolves the specified {@code promise} with the specified {@code result} so that the {@code promise} is
     * marked as 'done'. If {@code promise} is done already, this method does the following:
     * <ul>
     *   <li>Log a warning about the failure, and</li>
     *   <li>Release {@code result} if it is {@linkplain ReferenceCounted a reference-counted object},
     *       such as {@link ByteBuf} and {@link FullHttpResponse}.</li>
     * </ul>
     * Note that a {@link Promise} can be done already even if you did not call this method in the following
     * cases:
     * <ul>
     *   <li>Invocation timeout - The invocation associated with the {@link Promise} has been timed out.</li>
     *   <li>User error - A service implementation called any of the following methods more than once:
     *     <ul>
     *       <li>{@link #resolvePromise(Promise, Object)}</li>
     *       <li>{@link #rejectPromise(Promise, Throwable)}</li>
     *       <li>{@link Promise#setSuccess(Object)}</li>
     *       <li>{@link Promise#setFailure(Throwable)}</li>
     *       <li>{@link Promise#cancel(boolean)}</li>
     *     </ul>
     *   </li>
     * </ul>
     */
    public void resolvePromise(Promise<?> promise, Object result) {
        @SuppressWarnings("unchecked")
        final Promise<Object> castPromise = (Promise<Object>) promise;

        if (castPromise.trySuccess(result)) {
            // Resolved successfully.
            return;
        }

        try {
            if (!(promise.cause() instanceof TimeoutException)) {
                // Log resolve failure unless it is due to a timeout.
                logger().warn("Failed to resolve a promise ({}) with {}", promise, result);
            }
        } finally {
            ReferenceCountUtil.safeRelease(result);
        }
    }

    /**
     * Rejects the specified {@code promise} with the specified {@code cause}. If {@code promise} is done
     * already, this method logs a warning about the failure. Note that a {@link Promise} can be done already
     * even if you did not call this method in the following cases:
     * <ul>
     *   <li>Invocation timeout - The invocation associated with the {@link Promise} has been timed out.</li>
     *   <li>User error - A service implementation called any of the following methods more than once:
     *     <ul>
     *       <li>{@link #resolvePromise(Promise, Object)}</li>
     *       <li>{@link #rejectPromise(Promise, Throwable)}</li>
     *       <li>{@link Promise#setSuccess(Object)}</li>
     *       <li>{@link Promise#setFailure(Throwable)}</li>
     *       <li>{@link Promise#cancel(boolean)}</li>
     *     </ul>
     *   </li>
     * </ul>
     */
    public void rejectPromise(Promise<?> promise, Throwable cause) {
        if (promise.tryFailure(cause)) {
            // Fulfilled successfully.
            return;
        }

        if (!(promise.cause() instanceof TimeoutException)) {
            // Log reject failure unless it is due to a timeout.
            logger().warn("Failed to reject a promise ({}) with {}", promise, cause, cause);
        }
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
