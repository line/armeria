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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.JavaVersionSpecific;
import com.linecorp.armeria.internal.common.RequestContextThreadLocal;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

/**
 * Provides information about a {@link Request}, its {@link Response} and related utilities.
 * A server-side {@link Request} has a {@link ServiceRequestContext} and
 * a client-side {@link Request} has a {@link ClientRequestContext}.
 */
public interface RequestContext {

    /**
     * Returns the context of the {@link Request} that is being handled in the current thread.
     *
     * @throws IllegalStateException if the context is unavailable in the current thread
     */
    static <T extends RequestContext> T current() {
        final T ctx = currentOrNull();
        if (ctx == null) {
            throw new IllegalStateException(RequestContext.class.getSimpleName() + " unavailable");
        }
        return ctx;
    }

    /**
     * Returns the context of the {@link Request} that is being handled in the current thread.
     *
     * @return the {@link RequestContext} available in the current thread, or {@code null} if unavailable.
     */
    @Nullable
    static <T extends RequestContext> T currentOrNull() {
        return RequestContextThreadLocal.get();
    }

    /**
     * Maps the context of the {@link Request} that is being handled in the current thread.
     *
     * @param mapper the {@link Function} that maps the {@link RequestContext}
     * @param defaultValueSupplier the {@link Supplier} that provides the value when the context is unavailable
     *                             in the current thread. If {@code null}, the {@code null} will be returned
     *                             when the context is unavailable in the current thread.
     */
    @Nullable
    static <T> T mapCurrent(
            Function<? super RequestContext, T> mapper, @Nullable Supplier<T> defaultValueSupplier) {

        final RequestContext ctx = currentOrNull();
        if (ctx != null) {
            return mapper.apply(ctx);
        }

        if (defaultValueSupplier != null) {
            return defaultValueSupplier.get();
        }

        return null;
    }

    /**
     * Returns the value mapped to the given {@link AttributeKey} or {@code null} if there's no value set by
     * {@link #setAttr(AttributeKey, Object)} or {@link #setAttrIfAbsent(AttributeKey, Object)}.
     */
    @Nullable
    <V> V attr(AttributeKey<V> key);

    /**
     * Associates the specified value with the given {@link AttributeKey} in this context.
     * If this context previously contained a mapping for the {@link AttributeKey},
     * the old value is replaced by the specified value. Set {@code null} not to iterate the mapping from
     * {@link #attrs()}.
     */
    <V> void setAttr(AttributeKey<V> key, @Nullable V value);

    /**
     * Associates the specified value with the given {@link AttributeKey} in this context only
     * if this context does not contain a mapping for the {@link AttributeKey}.
     *
     * @return {@code null} if there was no mapping for the {@link AttributeKey} or the old value if there's
     *         a mapping for the {@link AttributeKey}.
     */
    @Nullable
    <V> V setAttrIfAbsent(AttributeKey<V> key, V value);

    /**
     * If the specified {@link AttributeKey} is not already associated with a value (or is mapped
     * to {@code null}), attempts to compute its value using the given mapping
     * function and stores it into this context.
     *
     * <p>If the mapping function returns {@code null}, no mapping is recorded.
     *
     * @return the current (existing or computed) value associated with
     *         the specified {@link AttributeKey}, or {@code null} if the computed value is {@code null}
     */
    @Nullable
    <V> V computeAttrIfAbsent(
            AttributeKey<V> key, Function<? super AttributeKey<V>, ? extends V> mappingFunction);

    /**
     * Returns the {@link Iterator} of all {@link Entry}s this context contains.
     */
    Iterator<Entry<AttributeKey<?>, Object>> attrs();

    /**
     * Returns the {@link HttpRequest} associated with this context, or {@code null} if there's no
     * {@link HttpRequest} associated with this context yet.
     */
    @Nullable
    HttpRequest request();

    /**
     * Returns the {@link RpcRequest} associated with this context, or {@code null} if there's no
     * {@link RpcRequest} associated with this context.
     */
    @Nullable
    RpcRequest rpcRequest();

    /**
     * Replaces the {@link HttpRequest} associated with this context with the specified one.
     * This method is useful to a decorator that manipulates HTTP request headers.
     *
     * <p>Note that it is a bad idea to change the values of the pseudo headers ({@code ":method"},
     * {@code ":path"}, {@code ":scheme"} and {@code ":authority"}) when replacing an {@link HttpRequest},
     * because the properties of this context, such as {@link #path()}, are unaffected by such an attempt.</p>
     *
     * @see HttpRequest#withHeaders(RequestHeaders)
     * @see HttpRequest#withHeaders(RequestHeadersBuilder)
     */
    void updateRequest(HttpRequest req);

    /**
     * Replaces the {@link RpcRequest} associated with this context with the specified one.
     * This method is useful to a decorator that manipulates an RPC call.
     */
    void updateRpcRequest(RpcRequest rpcReq);

    /**
     * Returns the {@link SessionProtocol} of the current {@link Request}.
     */
    SessionProtocol sessionProtocol();

    /**
     * Returns the remote address of this request, or {@code null} if the connection is not established yet.
     */
    @Nullable
    <A extends SocketAddress> A remoteAddress();

    /**
     * Returns the local address of this request, or {@code null} if the connection is not established yet.
     */
    @Nullable
    <A extends SocketAddress> A localAddress();

    /**
     * The {@link SSLSession} for this request if the connection is made over TLS, or {@code null} if
     * the connection is not established yet or the connection is not a TLS connection.
     */
    @Nullable
    SSLSession sslSession();

    /**
     * Returns the {@link RequestId} of the current {@link Request} and {@link Response} pair.
     */
    RequestId id();

    /**
     * Returns the HTTP method of the current {@link Request}.
     */
    HttpMethod method();

    /**
     * Returns the absolute path part of the current {@link Request} URI, excluding the query part,
     * as defined in <a href="https://tools.ietf.org/html/rfc3986">RFC3986</a>.
     */
    String path();

    /**
     * Returns the absolute path part of the current {@link Request} URI, excluding the query part,
     * decoded in UTF-8.
     */
    String decodedPath();

    /**
     * Returns the query part of the current {@link Request} URI, without the leading {@code '?'},
     * as defined in <a href="https://tools.ietf.org/html/rfc3986">RFC3986</a>.
     */
    @Nullable
    String query();

    /**
     * Returns the {@link RequestLogAccess} that provides the access to the {@link RequestLog}, which
     * contains the information collected while processing the current {@link Request}.
     */
    RequestLogAccess log();

    /**
     * Returns the {@link RequestLogBuilder} that collects the information about the current {@link Request}.
     */
    RequestLogBuilder logBuilder();

    /**
     * Returns the {@link MeterRegistry} that collects various stats.
     */
    MeterRegistry meterRegistry();

    /**
     * Returns the {@link Executor} that is handling the current {@link Request}.
     */
    default Executor executor() {
        // The implementation is the same as eventLoop but we expose as an Executor as well given
        // how much easier it is to write tests for an Executor (i.e.,
        // when(ctx.executor()).thenReturn(MoreExecutors.directExecutor()));
        return eventLoop();
    }

    /**
     * Returns the {@link EventLoop} that is handling the current {@link Request}.
     */
    EventLoop eventLoop();

    /**
     * Returns the {@link ByteBufAllocator} for this {@link RequestContext}. Any buffers created by this
     * {@link ByteBufAllocator} must be
     * <a href="https://netty.io/wiki/reference-counted-objects.html">reference-counted</a>. If you don't know
     * what this means, you should probably use {@code byte[]} or {@link ByteBuffer} directly instead
     * of calling this method.
     */
    default ByteBufAllocator alloc() {
        throw new UnsupportedOperationException("No ByteBufAllocator available for this RequestContext.");
    }

    /**
     * Returns an {@link Executor} that will make sure this {@link RequestContext} is set as the current
     * context before executing any callback. This should almost always be used for executing asynchronous
     * callbacks in service code to make sure features that require the {@link RequestContext} work properly.
     * Most asynchronous libraries like {@link CompletableFuture} provide methods that accept an
     * {@link Executor} to run callbacks on.
     */
    default Executor contextAwareExecutor() {
        // The implementation is the same as contextAwareEventLoop but we expose as an Executor as well given
        // how common it is to use only as an Executor and it becomes much easier to write tests for an
        // Executor (i.e., when(ctx.contextAwareExecutor()).thenReturn(MoreExecutors.directExecutor()));
        return contextAwareEventLoop();
    }

    /**
     * Returns an {@link EventLoop} that will make sure this {@link RequestContext} is set as the current
     * context before executing any callback.
     */
    default EventLoop contextAwareEventLoop() {
        return new RequestContextAwareEventLoop(this, eventLoop());
    }

    /**
     * Pushes the specified context to the thread-local stack. To pop the context from the stack, call
     * {@link SafeCloseable#close()}, which can be done using a {@code try-with-resources} block.
     *
     * <p>This method may throw an {@link IllegalStateException} according to the status of the current
     * thread-local. Please see {@link ServiceRequestContext#push()} and
     * {@link ClientRequestContext#push()} to find out the satisfying conditions.
     *
     * @deprecated Use {@link #push()}.
     */
    @Deprecated
    static SafeCloseable push(RequestContext ctx) {
        return ctx.push();
    }

    /**
     * Pushes the specified context to the thread-local stack. To pop the context from the stack, call
     * {@link SafeCloseable#close()}, which can be done using a {@code try-with-resources} block.
     *
     * <p>This method may throw an {@link IllegalStateException} according to the status of the current
     * thread-local. Please see {@link ServiceRequestContext#push()} and
     * {@link ClientRequestContext#push()} to find out the satisfying conditions.
     *
     * @deprecated Use {@link #push()}.
     */
    @Deprecated
    static SafeCloseable push(RequestContext ctx, boolean runCallbacks) {
        return ctx.push();
    }

    /**
     * Pushes the specified context to the thread-local stack. To pop the context from the stack, call
     * {@link SafeCloseable#close()}, which can be done using a {@code try-with-resources} block:
     * <pre>{@code
     * try (SafeCloseable ignored = ctx.push()) {
     *     ...
     * }
     * }</pre>
     *
     * <p>This method may throw an {@link IllegalStateException} according to the status of the current
     * thread-local. Please see {@link ServiceRequestContext#push()} and
     * {@link ClientRequestContext#push()} to find out the satisfying conditions.
     */
    SafeCloseable push();

    /**
     * Pushes the specified context to the thread-local stack. To pop the context from the stack, call
     * {@link SafeCloseable#close()}, which can be done using a {@code try-with-resources} block:
     *
     * <p>This method may throw an {@link IllegalStateException} according to the status of the current
     * thread-local. Please see {@link ServiceRequestContext#push()} and
     * {@link ClientRequestContext#push()} to find out the satisfying conditions.
     *
     * @param runCallbacks This is not used.
     *
     * @deprecated Use {@link #push()}.
     */
    @Deprecated
    default SafeCloseable push(boolean runCallbacks) {
        return push();
    }

    /**
     * Pushes this context to the thread-local stack. To pop the context from the stack,
     * call {@link SafeCloseable#close()}, which can be done using a {@code try-with-resources} block.
     *
     * <p>This method may throw an {@link IllegalStateException} according to the status of the current
     * thread-local. Please see {@link ServiceRequestContext#push()} and
     * {@link ClientRequestContext#push()} to find out the satisfying conditions.
     *
     * @deprecated Use {@link #push()}.
     */
    @Deprecated
    default SafeCloseable pushIfAbsent() {
        return push();
    }

    /**
     * Replaces the current {@link RequestContext} in the thread-local with this context without any validation.
     * This method also does not run any callbacks.
     *
     * <p><strong>Note:</strong> Do not use this if you don't know what you are doing. This method does not
     * prevent the situation where a wrong {@link RequestContext} is pushed into the thread-local.
     * Use {@link #push()} instead.
     *
     * @see ClientRequestContext#push()
     * @see ServiceRequestContext#push()
     */
    default SafeCloseable replace() {
        final RequestContext oldCtx = RequestContextThreadLocal.getAndSet(this);
        if (oldCtx == null) {
            return RequestContextThreadLocal::remove;
        }
        return () -> RequestContextThreadLocal.set(oldCtx);
    }

    /**
     * Returns an {@link Executor} that will execute callbacks in the given {@code executor}, making sure to
     * propagate the current {@link RequestContext} into the callback execution. It is generally preferred to
     * use {@link #contextAwareEventLoop()} to ensure the callback stays on the same thread as well.
     */
    default Executor makeContextAware(Executor executor) {
        return runnable -> executor.execute(makeContextAware(runnable));
    }

    /**
     * Returns an {@link ExecutorService} that will execute callbacks in the given {@code executor}, making
     * sure to propagate the current {@link RequestContext} into the callback execution.
     */
    default ExecutorService makeContextAware(ExecutorService executor) {
        return new RequestContextAwareExecutorService(this, executor);
    }

    /**
     * Returns a {@link ScheduledExecutorService} that will execute callbacks in the given {@code executor},
     * making sure to propagate the current {@link RequestContext} into the callback execution.
     */
    default ScheduledExecutorService makeContextAware(ScheduledExecutorService executor) {
        return new RequestContextAwareScheduledExecutorService(this, executor);
    }

    /**
     * Returns a {@link Callable} that makes sure the current {@link RequestContext} is set and then invokes
     * the input {@code callable}.
     */
    default <T> Callable<T> makeContextAware(Callable<T> callable) {
        return () -> {
            try (SafeCloseable ignored = push()) {
                return callable.call();
            }
        };
    }

    /**
     * Returns a {@link Runnable} that makes sure the current {@link RequestContext} is set and then invokes
     * the input {@code runnable}.
     */
    default Runnable makeContextAware(Runnable runnable) {
        return () -> {
            try (SafeCloseable ignored = push()) {
                runnable.run();
            }
        };
    }

    /**
     * Returns a {@link Function} that makes sure the current {@link RequestContext} is set and then invokes
     * the input {@code function}.
     */
    default <T, R> Function<T, R> makeContextAware(Function<T, R> function) {
        return t -> {
            try (SafeCloseable ignored = push()) {
                return function.apply(t);
            }
        };
    }

    /**
     * Returns a {@link BiFunction} that makes sure the current {@link RequestContext} is set and then invokes
     * the input {@code function}.
     */
    default <T, U, V> BiFunction<T, U, V> makeContextAware(BiFunction<T, U, V> function) {
        return (t, u) -> {
            try (SafeCloseable ignored = push()) {
                return function.apply(t, u);
            }
        };
    }

    /**
     * Returns a {@link Consumer} that makes sure the current {@link RequestContext} is set and then invokes
     * the input {@code action}.
     */
    default <T> Consumer<T> makeContextAware(Consumer<T> action) {
        return t -> {
            try (SafeCloseable ignored = push()) {
                action.accept(t);
            }
        };
    }

    /**
     * Returns a {@link BiConsumer} that makes sure the current {@link RequestContext} is set and then invokes
     * the input {@code action}.
     */
    default <T, U> BiConsumer<T, U> makeContextAware(BiConsumer<T, U> action) {
        return (t, u) -> {
            try (SafeCloseable ignored = push()) {
                action.accept(t, u);
            }
        };
    }

    /**
     * Returns a {@link FutureListener} that makes sure the current {@link RequestContext} is set and then
     * invokes the input {@code listener}.
     *
     * @deprecated Use {@link CompletableFuture} instead.
     */
    @Deprecated
    default <T> FutureListener<T> makeContextAware(FutureListener<T> listener) {
        return future -> {
            try (SafeCloseable ignored = push()) {
                listener.operationComplete(future);
            }
        };
    }

    /**
     * Returns a {@link ChannelFutureListener} that makes sure the current {@link RequestContext} is set and
     * then invokes the input {@code listener}.
     *
     * @deprecated Use {@link CompletableFuture} instead.
     */
    @Deprecated
    default ChannelFutureListener makeContextAware(ChannelFutureListener listener) {
        return future -> {
            try (SafeCloseable ignored = push()) {
                listener.operationComplete(future);
            }
        };
    }

    /**
     * Returns a {@link GenericFutureListener} that makes sure the current {@link RequestContext} is set and
     * then invokes the input {@code listener}. Unlike other versions of {@code makeContextAware}, this one will
     * invoke the listener with the future's result even if the context has already been timed out.
     * @deprecated Use {@link CompletableFuture} instead.
     */
    @Deprecated
    default <T extends Future<?>> GenericFutureListener<T> makeContextAware(GenericFutureListener<T> listener) {
        return future -> {
            try (SafeCloseable ignored = push()) {
                listener.operationComplete(future);
            }
        };
    }

    /**
     * Returns a {@link CompletionStage} that makes sure the current {@link RequestContext} is set and
     * then invokes the input {@code stage}.
     */
    default <T> CompletionStage<T> makeContextAware(CompletionStage<T> stage) {
        final CompletableFuture<T> future = JavaVersionSpecific.get().newRequestContextAwareFuture(this);
        stage.handle((result, cause) -> {
            try (SafeCloseable ignored = push()) {
                if (cause != null) {
                    future.completeExceptionally(cause);
                } else {
                    future.complete(result);
                }
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
            return null;
        });
        return future;
    }

    /**
     * Returns a {@link CompletableFuture} that makes sure the current {@link RequestContext} is set and
     * then invokes the input {@code future}.
     */
    default <T> CompletableFuture<T> makeContextAware(CompletableFuture<T> future) {
        return makeContextAware((CompletionStage<T>) future).toCompletableFuture();
    }

    /**
     * Returns a {@link Logger} which prepends this {@link RequestContext} to the log message.
     *
     * @param logger the {@link Logger} to decorate.
     */
    default Logger makeContextAware(Logger logger) {
        return new RequestContextAwareLogger(this, requireNonNull(logger, "logger"));
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
     *
     * @deprecated Use {@link CompletableFuture} instead.
     */
    @Deprecated
    default void resolvePromise(Promise<?> promise, Object result) {
        @SuppressWarnings("unchecked")
        final Promise<Object> castPromise = (Promise<Object>) promise;

        if (castPromise.trySuccess(result)) {
            // Resolved successfully.
            return;
        }

        try {
            if (!(promise.cause() instanceof TimeoutException)) {
                // Log resolve failure unless it is due to a timeout.
                LoggerFactory.getLogger(RequestContext.class).warn(
                        "Failed to resolve a completed promise ({}) with {}", promise, result);
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
     *
     * @deprecated Use {@link CompletableFuture} instead.
     */
    @Deprecated
    default void rejectPromise(Promise<?> promise, Throwable cause) {
        if (promise.tryFailure(cause)) {
            // Fulfilled successfully.
            return;
        }

        final Throwable firstCause = promise.cause();
        if (firstCause instanceof TimeoutException) {
            // Timed out already.
            return;
        }

        if (Exceptions.isExpected(cause)) {
            // The exception that was thrown after firstCause (often a transport-layer exception)
            // was a usual expected exception, not an error.
            return;
        }

        LoggerFactory.getLogger(RequestContext.class).warn(
                "Failed to reject a completed promise ({}) with {}", promise, cause, cause);
    }

    /**
     * Creates a new {@link RequestContext} whose properties and {@link #attrs()} are copied from this
     * {@link RequestContext}, except having a different pair of {@link HttpRequest} and {@link RpcRequest}
     * and its own {@link RequestLog}.
     */
    RequestContext newDerivedContext(RequestId id, @Nullable HttpRequest req, @Nullable RpcRequest rpcReq);
}
