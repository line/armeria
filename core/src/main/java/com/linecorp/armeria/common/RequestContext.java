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

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
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

import javax.net.ssl.SSLSession;

import org.slf4j.Logger;

import com.google.errorprone.annotations.MustBeClosed;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.common.util.DomainSocketAddress;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.Unwrappable;
import com.linecorp.armeria.internal.common.JavaVersionSpecific;
import com.linecorp.armeria.internal.common.RequestContextUtil;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.EventLoop;
import io.netty.util.AttributeKey;

/**
 * Provides information about a {@link Request}, its {@link Response} and related utilities.
 * A server-side {@link Request} has a {@link ServiceRequestContext} and
 * a client-side {@link Request} has a {@link ClientRequestContext}.
 */
public interface RequestContext extends Unwrappable {

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
        return RequestContextUtil.get();
    }

    /**
     * Returns an {@link Executor} that will execute callbacks in the given {@code executor}, propagating
     * the caller's {@link RequestContext} (if any) into the callback execution.
     * If this executor is only used from a single request then it's better to use
     * {@link #makeContextAware(Executor)}
     */
    static Executor makeContextPropagating(Executor executor) {
        return PropagatingContextAwareExecutor.of(executor);
    }

    /**
     * Returns an {@link ExecutorService} that will execute callbacks in the given {@code executor}, propagating
     * the caller's {@link RequestContext} (if any) into the callback execution.
     * If this executor service is only used from a single request then it's better to use
     * {@link #makeContextAware(ExecutorService)}
     */
    static ExecutorService makeContextPropagating(ExecutorService executor) {
        return PropagatingContextAwareExecutorService.of(executor);
    }

    /**
     * Returns a {@link ScheduledExecutorService} that will execute callbacks in the given {@code executor},
     * propagating the caller's {@link RequestContext} (if any) into the callback execution.
     * If this executor service is only used from a single request then it's better to use
     * {@link #makeContextAware(ScheduledExecutorService)}
     */
    static ScheduledExecutorService makeContextPropagating(ScheduledExecutorService executor) {
        return PropagatingContextAwareScheduledExecutorService.of(executor);
    }

    /**
     * Returns a {@link BlockingTaskExecutor} that will execute callbacks in the given {@code executor},
     * propagating the caller's {@link RequestContext} (if any) into the callback execution.
     * If this executor service is only used from a single request then it's better to use
     * {@link #makeContextAware(BlockingTaskExecutor)}
     */
    static BlockingTaskExecutor makeContextPropagating(BlockingTaskExecutor executor) {
        return PropagatingContextAwareBlockingTaskExecutor.of(executor);
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
            Function<? super RequestContext, T> mapper,
            @Nullable Supplier<@Nullable T> defaultValueSupplier) {

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
     * Returns the root {@link ServiceRequestContext} of this context.
     *
     * @return the root {@link ServiceRequestContext}, or {@code null} if this context was not created
     *         in the context of a server request.
     */
    @Nullable
    ServiceRequestContext root();

    /**
     * Returns the value associated with the given {@link AttributeKey} or {@code null} if there's no value
     * set by {@link #setAttr(AttributeKey, Object)}.
     *
     * <h4>Searching for attributes in a root context</h4>
     *
     * <p>Note: This section applies only to a {@link ClientRequestContext}. A {@link ServiceRequestContext}
     * always has itself as a {@link #root()}.</p>
     *
     * <p>If the value does not exist in this context but only in {@link #root()},
     * this method will return the value from the {@link #root()}.
     * <pre>{@code
     * ClientRequestContext ctx = ...;
     * assert ctx.root().attr(KEY).equals("root");
     * assert ctx.attr(KEY).equals("root");
     * assert ctx.ownAttr(KEY) == null;
     * }</pre>
     * If the value exists both in this context and {@link #root()},
     * this method will return the value from this context.
     * <pre>{@code
     * ClientRequestContext ctx = ...;
     * assert ctx.root().attr(KEY).equals("root");
     * assert ctx.ownAttr(KEY).equals("child");
     * assert ctx.attr(KEY).equals("child");
     * }</pre>
     *
     * @see #ownAttr(AttributeKey)
     */
    @Nullable
    <V> V attr(AttributeKey<V> key);

    /**
     * Returns the value associated with the given {@link AttributeKey} or {@code null} if there's no value
     * set by {@link #setAttr(AttributeKey, Object)}.
     *
     * <p>Unlike {@link #attr(AttributeKey)}, this does not search in {@link #root()}.</p>
     *
     * @see #attr(AttributeKey)
     */
    @Nullable
    <V> V ownAttr(AttributeKey<V> key);

    /**
     * Returns {@code true} if and only if the value associated with the specified {@link AttributeKey} is
     * not {@code null}.
     *
     * @see #hasOwnAttr(AttributeKey)
     */
    default boolean hasAttr(AttributeKey<?> key) {
        return attr(key) != null;
    }

    /**
     * Returns {@code true} if and only if the value associated with the specified {@link AttributeKey} is
     * not {@code null}.
     *
     * <p>Unlike {@link #hasAttr(AttributeKey)}, this does not search in {@link #root()}.</p>
     *
     * @see #hasAttr(AttributeKey)
     */
    default boolean hasOwnAttr(AttributeKey<?> key) {
        return ownAttr(key) != null;
    }

    /**
     * Returns the {@link Iterator} of all {@link Entry}s this context contains.
     *
     * <h4>Searching for attributes in a root context</h4>
     *
     * <p>Note: This section applies only to a {@link ClientRequestContext}. A {@link ServiceRequestContext}
     * always has itself as a {@link #root()}.</p>
     *
     * <p>The {@link Iterator} returned by this method will also yield the {@link Entry}s from the
     * {@link #root()} except those whose {@link AttributeKey} exist already in this context, e.g.
     * <pre>{@code
     * ClientRequestContext ctx = ...;
     * assert ctx.ownAttr(KEY_A).equals("child_a");
     * assert ctx.root().attr(KEY_A).equals("root_a");
     * assert ctx.root().attr(KEY_B).equals("root_b");
     *
     * Iterator<Entry<AttributeKey<?>, Object>> attrs = ctx.attrs();
     * assert attrs.next().getValue().equals("child_a"); // KEY_A
     * // Skip KEY_A in the root.
     * assert attrs.next().getValue().equals("root_b"); // KEY_B
     * assert attrs.hasNext() == false;
     * }</pre>
     * Please note that any changes made to the {@link Entry} returned by {@link Iterator#next()} never
     * affects the {@link Entry} owned by {@link #root()}. For example:
     * <pre>{@code
     * ClientRequestContext ctx = ...;
     * assert ctx.root().attr(KEY).equals("root");
     * assert ctx.ownAttr(KEY) == null;
     *
     * Iterator<Entry<AttributeKey<?>, Object>> attrs = ctx.attrs();
     * Entry<AttributeKey<?>, Object> next = attrs.next();
     * assert next.getKey() == KEY;
     * // Overriding the root entry creates the client context's own entry.
     * next.setValue("child");
     * assert ctx.attr(KEY).equals("child");
     * assert ctx.ownAttr(KEY).equals("child");
     * // root attribute remains unaffected.
     * assert ctx.root().attr(KEY).equals("root");
     * }</pre>
     * If you want to change the value from the root while iterating, please call
     * {@link #attrs()} from {@link #root()}.
     * <pre>{@code
     * ClientRequestContext ctx = ...;
     * assert ctx.root().attr(KEY).equals("root");
     * assert ctx.ownAttr(KEY) == null;
     *
     * // Call attrs() from the root to set a value directly while iterating.
     * Iterator<Entry<AttributeKey<?>, Object>> attrs = ctx.root().attrs();
     * Entry<AttributeKey<?>, Object> next = attrs.next();
     * assert next.getKey() == KEY;
     * next.setValue("another_root");
     * // The ctx does not have its own attribute.
     * assert ctx.ownAttr(KEY) == null;
     * assert ctx.attr(KEY).equals("another_root");
     * }</pre>
     *
     * @see #ownAttrs()
     */
    Iterator<Entry<AttributeKey<?>, Object>> attrs();

    /**
     * Returns the {@link Iterator} of all {@link Entry}s this context contains.
     *
     * <p>Unlike {@link #attrs()}, this does not iterate {@link #root()}.</p>
     *
     * @see #attrs()
     */
    Iterator<Entry<AttributeKey<?>, Object>> ownAttrs();

    /**
     * Associates the specified value with the given {@link AttributeKey} in this context.
     * If this context previously contained a mapping for the {@link AttributeKey}, the old value is replaced
     * by the specified value. Set {@code null} not to iterate the mapping from {@link #attrs()}.
     *
     * @return the old value that has been replaced if there's a mapping for the specified key in this context
     *         or its {@link #root()}, or {@code null} otherwise.
     */
    @Nullable
    <V> V setAttr(AttributeKey<V> key, @Nullable V value);

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
     *
     * @return an {@link InetSocketAddress}, a {@link DomainSocketAddress} or {@code null}
     */
    @Nullable
    InetSocketAddress remoteAddress();

    /**
     * Returns the local address of this request, or {@code null} if the connection is not established yet.
     *
     * @return an {@link InetSocketAddress}, a {@link DomainSocketAddress} or {@code null}
     */
    @Nullable
    InetSocketAddress localAddress();

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
     * as defined in <a href="https://datatracker.ietf.org/doc/rfc3986/">RFC3986</a>.
     */
    String path();

    /**
     * Returns the absolute path part of the current {@link Request} URI, excluding the query part,
     * decoded in UTF-8.
     */
    String decodedPath();

    /**
     * Returns the query part of the current {@link Request} URI, without the leading {@code '?'},
     * as defined in <a href="https://datatracker.ietf.org/doc/rfc3986/">RFC3986</a>.
     */
    @Nullable
    String query();

    /**
     * Returns the {@link URI} associated with the current {@link Request}.
     *
     * @see ServiceRequestContext#uri()
     * @see ClientRequestContext#uri()
     */
    @UnstableApi
    URI uri();

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
     * Returns the amount of time to wait in millis before aborting an {@link HttpRequest} when
     * its corresponding {@link HttpResponse} is complete.
     */
    @UnstableApi
    long requestAutoAbortDelayMillis();

    /**
     * Sets the amount of time to wait before aborting an {@link HttpRequest} when
     * its corresponding {@link HttpResponse} is complete. Note that this method must be
     * called before the {@link HttpResponse} is completed to take effect.
     */
    @UnstableApi
    default void setRequestAutoAbortDelay(Duration delay) {
        requireNonNull(delay, "delay");
        setRequestAutoAbortDelayMillis(delay.toMillis());
    }

    /**
     * Sets the amount of time in millis to wait before aborting an {@link HttpRequest} when
     * its corresponding {@link HttpResponse} is complete. Note that this method must be
     * called before the {@link HttpResponse} is completed to take effect.
     */
    @UnstableApi
    void setRequestAutoAbortDelayMillis(long delayMillis);

    /**
     * Cancels the current {@link Request} with a {@link Throwable}.
     */
    void cancel(Throwable cause);

    /**
     * Cancels the current {@link Request}.
     */
    void cancel();

    /**
     * Times out the current {@link Request}.
     */
    void timeoutNow();

    /**
     * Returns the cause of cancellation, {@code null} if the request has not been cancelled.
     * Note that there is no guarantee that the cancellation cause is equivalent to the cause of failure
     * for {@link HttpRequest} or {@link HttpResponse}. Refer to {@link RequestLog#requestCause()}
     * or {@link RequestLog#responseCause()} for the exact reason why a request or response failed.
     */
    @Nullable
    Throwable cancellationCause();

    /**
     * Returns whether this {@link RequestContext} has been cancelled.
     */
    default boolean isCancelled() {
        return cancellationCause() != null;
    }

    /**
     * Returns whether this {@link RequestContext} has been timed-out, that is the cancellation cause is an
     * instance of {@link TimeoutException}.
     */
    default boolean isTimedOut() {
        return cancellationCause() instanceof TimeoutException;
    }

    /**
     * Returns the {@link ContextAwareEventLoop} that is handling the current {@link Request}.
     * The {@link ContextAwareEventLoop} sets this {@link RequestContext} as the current context
     * before executing any submitted tasks. If you want to use {@link EventLoop} without setting this context,
     * call {@link ContextAwareEventLoop#withoutContext()} and use the returned {@link EventLoop}.
     */
    ContextAwareEventLoop eventLoop();

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
     * Returns the {@link ExchangeType} that determines whether to stream an {@link HttpRequest} or
     * {@link HttpResponse}.
     */
    @UnstableApi
    ExchangeType exchangeType();

    /**
     * Initiates connection shutdown and returns {@link CompletableFuture} that completes when the connection
     * associated with this context is closed.
     *
     * @see ClientRequestContext#initiateConnectionShutdown()
     * @see ServiceRequestContext#initiateConnectionShutdown()
     */
    @UnstableApi
    CompletableFuture<Void> initiateConnectionShutdown();

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
    @MustBeClosed
    SafeCloseable push();

    /**
     * Adds a hook which is invoked whenever this {@link RequestContext} is pushed to the
     * {@link RequestContextStorage}. The {@link AutoCloseable} returned by {@code contextHook} will be called
     * whenever this {@link RequestContext} is popped from the {@link RequestContextStorage}.
     * This method is useful when you need to propagate a custom context in this {@link RequestContext}'s scope.
     *
     * <p>Note:
     * <ul>
     *   <li>The {@code contextHook} is not invoked when this {@link #hook(Supplier)} method is called
     *       thus you need to call it yourself if you want to apply the hook in the current thread. </li>
     *   <li>This operation is highly performance-sensitive operation, and thus it's not a good idea to run a
     *       time-consuming task.</li>
     * </ul>
     */
    @UnstableApi
    void hook(Supplier<? extends AutoCloseable> contextHook);

    /**
     * Returns the hook which is invoked whenever this {@link RequestContext} is pushed to the
     * {@link RequestContextStorage}. The {@link SafeCloseable} returned by the {@link Supplier} will be
     * called whenever this {@link RequestContext} is popped from the {@link RequestContextStorage}.
     */
    @UnstableApi
    Supplier<AutoCloseable> hook();

    @Override
    default RequestContext unwrap() {
        return (RequestContext) Unwrappable.super.unwrap();
    }

    @Override
    default RequestContext unwrapAll() {
        return (RequestContext) Unwrappable.super.unwrapAll();
    }

    /**
     * Immediately run a given {@link Runnable} with this context.
     */
    default void run(Runnable runnable) {
        requireNonNull(runnable, "runnable");
        try (SafeCloseable ignored = push()) {
            runnable.run();
        }
    }

    /**
     * Immediately call a given {@link Callable} with this context.
     */
    default <T> T run(Callable<T> callable) throws Exception {
        requireNonNull(callable, "callable");
        try (SafeCloseable ignored = push()) {
            return callable.call();
        }
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
    @MustBeClosed
    default SafeCloseable replace() {
        final RequestContext oldCtx = RequestContextUtil.getAndSet(this);
        return () -> RequestContextUtil.pop(this, oldCtx);
    }

    /**
     * Returns a {@link ContextAwareExecutor} that will execute callbacks in the given {@code executor},
     * making sure to propagate the current {@link RequestContext} into the callback execution. It is generally
     * preferred to use {@link #eventLoop()} to ensure the callback stays on the same thread as well.
     */
    default ContextAwareExecutor makeContextAware(Executor executor) {
        requireNonNull(executor, "executor");
        return ContextAwareExecutor.of(this, executor);
    }

    /**
     * Returns a {@link ContextAwareExecutorService} that will execute callbacks in the given {@code executor},
     * making sure to propagate this {@link RequestContext} into the callback execution.
     * If this executor service will be used for callbacks from several requests, use
     * {@link #makeContextPropagating(ExecutorService)} instead.
     */
    default ContextAwareExecutorService makeContextAware(ExecutorService executor) {
        return ContextAwareExecutorService.of(this, executor);
    }

    /**
     * Returns a {@link ContextAwareScheduledExecutorService} that will execute callbacks in the given
     * {@code executor}, making sure to propagate this {@link RequestContext} into the callback execution.
     * If this executor service will be used for callbacks from several requests, use
     * {@link #makeContextPropagating(ScheduledExecutorService)} instead.
     */
    default ContextAwareScheduledExecutorService makeContextAware(ScheduledExecutorService executor) {
        return ContextAwareScheduledExecutorService.of(this, executor);
    }

    /**
     * Returns a {@link ContextAwareBlockingTaskExecutor} that will execute callbacks in the given
     * {@code executor}, making sure to propagate this {@link RequestContext} into the callback execution.
     * If this executor service will be used for callbacks from several requests, use
     * {@link #makeContextPropagating(BlockingTaskExecutor)} instead.
     */
    default ContextAwareBlockingTaskExecutor makeContextAware(BlockingTaskExecutor executor) {
        return ContextAwareBlockingTaskExecutor.of(this, executor);
    }

    /**
     * Returns a {@link ContextAwareCallable} that makes sure the current {@link RequestContext} is
     * set and then invokes the input {@code callable}.
     */
    default <T> Callable<T> makeContextAware(Callable<T> callable) {
        return ContextAwareCallable.of(this, callable);
    }

    /**
     * Returns a {@link ContextAwareRunnable} that makes sure the current {@link RequestContext} is
     * set and then invokes the input {@code runnable}.
     */
    default Runnable makeContextAware(Runnable runnable) {
        return ContextAwareRunnable.of(this, runnable);
    }

    /**
     * Returns a {@link ContextAwareFunction} that makes sure the current {@link RequestContext} is
     * set and then invokes the input {@code function}.
     */
    default <T, R> Function<T, R> makeContextAware(Function<T, R> function) {
        return ContextAwareFunction.of(this, function);
    }

    /**
     * Returns a {@link ContextAwareBiFunction} that makes sure the current {@link RequestContext} is
     * set and then invokes the input {@code function}.
     */
    default <T, U, V> BiFunction<T, U, V> makeContextAware(BiFunction<T, U, V> function) {
        return ContextAwareBiFunction.of(this, function);
    }

    /**
     * Returns a {@link ContextAwareConsumer} that makes sure the current {@link RequestContext} is
     * set and then invokes the input {@code action}.
     */
    default <T> Consumer<T> makeContextAware(Consumer<T> action) {
        return ContextAwareConsumer.of(this, action);
    }

    /**
     * Returns a {@link ContextAwareBiConsumer} that makes sure the current {@link RequestContext} is
     * set and then invokes the input {@code action}.
     */
    default <T, U> BiConsumer<T, U> makeContextAware(BiConsumer<T, U> action) {
        return ContextAwareBiConsumer.of(this, action);
    }

    /**
     * Returns a {@link CompletionStage} that makes sure the current {@link RequestContext} is set and
     * then invokes the input {@code stage}.
     */
    default <T> CompletionStage<T> makeContextAware(CompletionStage<T> stage) {
        requireNonNull(stage, "stage");
        if (stage instanceof ContextHolder) {
            final RequestContext context = ((ContextHolder) stage).context();
            if (this == context) {
                return stage;
            }
            if (root() != context.root()) {
                throw new IllegalArgumentException(
                        "cannot create a context aware future using " + stage);
            }
        }
        final CompletableFuture<T> future = JavaVersionSpecific.get().newContextAwareFuture(this);
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
        requireNonNull(future, "future");
        return makeContextAware((CompletionStage<T>) future).toCompletableFuture();
    }

    /**
     * Returns a {@link Logger} which prepends this {@link RequestContext} to the log message.
     *
     * @param logger the {@link Logger} to decorate.
     */
    default Logger makeContextAware(Logger logger) {
        return ContextAwareLogger.of(this, logger);
    }
}
