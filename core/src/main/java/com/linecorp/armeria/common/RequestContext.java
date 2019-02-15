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

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.Attribute;
import io.netty.util.AttributeMap;
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
public interface RequestContext extends AttributeMap {

    /**
     * Creates a new derived {@link RequestContext} which only the {@link RequestLog}
     * is different from the deriving context. Note that the references of {@link Attribute}s
     * in the {@link #attrs()} are copied as well.
     */
    RequestContext newDerivedContext();

    /**
     * Creates a new derived {@link RequestContext} with the specified {@link Request} which the
     * {@link RequestLog} is different from the deriving context.
     * Note that the references of {@link Attribute}s in the {@link #attrs()} are copied as well.
     */
    RequestContext newDerivedContext(Request request);

    /**
     * Returns the context of the {@link Request} that is being handled in the current thread.
     *
     * @throws IllegalStateException if the context is unavailable in the current thread
     */
    static <T extends RequestContext> T current() {
        final T ctx = RequestContextThreadLocal.get();
        if (ctx == null) {
            throw new IllegalStateException(RequestContext.class.getSimpleName() + " unavailable");
        }
        return ctx;
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

        final RequestContext ctx = RequestContextThreadLocal.get();
        if (ctx != null) {
            return mapper.apply(ctx);
        }

        if (defaultValueSupplier != null) {
            return defaultValueSupplier.get();
        }

        return null;
    }

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
     * Returns the {@link Request} associated with this context.
     */
    <T extends Request> T request();

    /**
     * Returns the {@link RequestLog} that contains the information about the current {@link Request}.
     */
    RequestLog log();

    /**
     * Returns the {@link RequestLogBuilder} that collects the information about the current {@link Request}.
     */
    RequestLogBuilder logBuilder();

    /**
     * Returns the {@link MeterRegistry} that collects various stats.
     */
    MeterRegistry meterRegistry();

    /**
     * Returns all {@link Attribute}s set in this context.
     */
    Iterator<Attribute<?>> attrs();

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
     * @deprecated Use {@link #push()}.
     */
    @Deprecated
    static SafeCloseable push(RequestContext ctx) {
        return ctx.push(true);
    }

    /**
     * Pushes the specified context to the thread-local stack. To pop the context from the stack, call
     * {@link SafeCloseable#close()}, which can be done using a {@code try-with-resources} block.
     *
     * @deprecated Use {@link #push(boolean)}.
     */
    @Deprecated
    static SafeCloseable push(RequestContext ctx, boolean runCallbacks) {
        return ctx.push(runCallbacks);
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
     * <p>The callbacks added by {@link #onEnter(Consumer)} and {@link #onExit(Consumer)} will be invoked
     * when the context is pushed to and removed from the thread-local stack respectively.
     *
     * <p>NOTE: In case of re-entrance, the callbacks will never run.
     */
    default SafeCloseable push() {
        return push(true);
    }

    /**
     * Pushes the specified context to the thread-local stack. To pop the context from the stack, call
     * {@link SafeCloseable#close()}, which can be done using a {@code try-with-resources} block:
     * <pre>{@code
     * try (PushHandle ignored = ctx.push(true)) {
     *     ...
     * }
     * }</pre>
     *
     * <p>NOTE: This method is only useful when it is undesirable to invoke the callbacks, such as replacing
     *          the current context with another. Prefer {@link #push()} otherwise.
     *
     * @param runCallbacks if {@code true}, the callbacks added by {@link #onEnter(Consumer)} and
     *                     {@link #onExit(Consumer)} will be invoked when the context is pushed to and
     *                     removed from the thread-local stack respectively.
     *                     If {@code false}, no callbacks will be executed.
     *                     NOTE: In case of re-entrance, the callbacks will never run.
     */
    default SafeCloseable push(boolean runCallbacks) {
        final RequestContext oldCtx = RequestContextThreadLocal.getAndSet(this);
        if (oldCtx == this) {
            // Reentrance
            return () -> { /* no-op */ };
        }

        if (runCallbacks) {
            if (oldCtx != null) {
                oldCtx.invokeOnChildCallbacks(this);
                invokeOnEnterCallbacks();
                return () -> {
                    invokeOnExitCallbacks();
                    RequestContextThreadLocal.set(oldCtx);
                };
            } else {
                invokeOnEnterCallbacks();
                return () -> {
                    invokeOnExitCallbacks();
                    RequestContextThreadLocal.remove();
                };
            }
        } else {
            if (oldCtx != null) {
                return () -> RequestContextThreadLocal.set(oldCtx);
            } else {
                return RequestContextThreadLocal::remove;
            }
        }
    }

    /**
     * Pushes this context to the thread-local stack if there is no current context. If there is and it is not
     * same with this context (i.e. not reentrance), this method will throw an {@link IllegalStateException}.
     * To pop the context from the stack, call {@link SafeCloseable#close()},
     * which can be done using a {@code try-with-resources} block.
     */
    default SafeCloseable pushIfAbsent() {
        final RequestContext currentRequestContext = RequestContextThreadLocal.get();
        if (currentRequestContext != null && currentRequestContext != this) {
            throw new IllegalStateException(
                    "Trying to call object wrapped with context " + this + ", but context is currently " +
                    "set to " + currentRequestContext + ". This means the callback was called from " +
                    "unexpected thread or forgetting to close previous context.");
        }
        return push();
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
     * Returns a {@link Callable} that makes sure the current {@link RequestContext} is set and then invokes
     * the input {@code callable}.
     */
    <T> Callable<T> makeContextAware(Callable<T> callable);

    /**
     * Returns a {@link Runnable} that makes sure the current {@link RequestContext} is set and then invokes
     * the input {@code runnable}.
     */
    Runnable makeContextAware(Runnable runnable);

    /**
     * Returns a {@link Function} that makes sure the current {@link RequestContext} is set and then invokes
     * the input {@code function}.
     */
    <T, R> Function<T, R> makeContextAware(Function<T, R> function);

    /**
     * Returns a {@link BiFunction} that makes sure the current {@link RequestContext} is set and then invokes
     * the input {@code function}.
     */
    <T, U, V> BiFunction<T, U, V> makeContextAware(BiFunction<T, U, V> function);

    /**
     * Returns a {@link Consumer} that makes sure the current {@link RequestContext} is set and then invokes
     * the input {@code action}.
     */
    <T> Consumer<T> makeContextAware(Consumer<T> action);

    /**
     * Returns a {@link BiConsumer} that makes sure the current {@link RequestContext} is set and then invokes
     * the input {@code action}.
     */
    <T, U> BiConsumer<T, U> makeContextAware(BiConsumer<T, U> action);

    /**
     * Returns a {@link FutureListener} that makes sure the current {@link RequestContext} is set and then
     * invokes the input {@code listener}.
     *
     * @deprecated Use {@link CompletableFuture} instead.
     */
    @Deprecated
    <T> FutureListener<T> makeContextAware(FutureListener<T> listener);

    /**
     * Returns a {@link ChannelFutureListener} that makes sure the current {@link RequestContext} is set and
     * then invokes the input {@code listener}.
     *
     * @deprecated Use {@link CompletableFuture} instead.
     */
    @Deprecated
    ChannelFutureListener makeContextAware(ChannelFutureListener listener);

    /**
     * Returns a {@link GenericFutureListener} that makes sure the current {@link RequestContext} is set and
     * then invokes the input {@code listener}. Unlike other versions of {@code makeContextAware}, this one will
     * invoke the listener with the future's result even if the context has already been timed out.
     * @deprecated Use {@link CompletableFuture} instead.
     */
    @Deprecated
    <T extends Future<?>> GenericFutureListener<T> makeContextAware(GenericFutureListener<T> listener);

    /**
     * Returns a {@link CompletionStage} that makes sure the current {@link CompletionStage} is set and
     * then invokes the input {@code stage}.
     */
    <T> CompletionStage<T> makeContextAware(CompletionStage<T> stage);

    /**
     * Returns a {@link CompletableFuture} that makes sure the current {@link CompletableFuture} is set and
     * then invokes the input {@code future}.
     */
    default <T> CompletableFuture<T> makeContextAware(CompletableFuture<T> future) {
        return makeContextAware((CompletionStage<T>) future).toCompletableFuture();
    }

    /**
     * Returns whether this {@link RequestContext} has been timed-out (e.g., when the corresponding request
     * passes a deadline).
     *
     * @deprecated Use {@link ServiceRequestContext#isTimedOut()}.
     */
    @Deprecated
    boolean isTimedOut();

    /**
     * Registers {@code callback} to be run when re-entering this {@link RequestContext}, usually when using
     * the {@link #makeContextAware} family of methods. Any thread-local state associated with this context
     * should be restored by this callback.
     *
     * @param callback a {@link Consumer} whose argument is this context
     */
    void onEnter(Consumer<? super RequestContext> callback);

    /**
     * Registers {@code callback} to be run when re-entering this {@link RequestContext}, usually when using
     * the {@link #makeContextAware} family of methods. Any thread-local state associated with this context
     * should be restored by this callback.
     *
     * @deprecated Use {@link #onEnter(Consumer)} instead.
     */
    @Deprecated
    default void onEnter(Runnable callback) {
        onEnter(ctx -> callback.run());
    }

    /**
     * Registers {@code callback} to be run when re-exiting this {@link RequestContext}, usually when using
     * the {@link #makeContextAware} family of methods. Any thread-local state associated with this context
     * should be reset by this callback.
     *
     * @param callback a {@link Consumer} whose argument is this context
     */
    void onExit(Consumer<? super RequestContext> callback);

    /**
     * Registers {@code callback} to be run when re-exiting this {@link RequestContext}, usually when using
     * the {@link #makeContextAware} family of methods. Any thread-local state associated with this context
     * should be reset by this callback.
     *
     * @deprecated Use {@link #onExit(Consumer)} instead.
     */
    @Deprecated
    default void onExit(Runnable callback) {
        onExit(ctx -> callback.run());
    }

    /**
     * Registers {@code callback} to be run when this context is replaced by a child context.
     * You could use this method to inherit an attribute of this context to the child contexts or
     * register a callback to the child contexts that may be created later:
     * <pre>{@code
     * ctx.onChild((curCtx, newCtx) -> {
     *     assert ctx == curCtx && curCtx != newCtx;
     *     // Inherit the value of the 'MY_ATTR' attribute to the child context.
     *     newCtx.attr(MY_ATTR).set(curCtx.attr(MY_ATTR).get());
     *     // Add a callback to the child context.
     *     newCtx.onExit(() -> { ... });
     * });
     * }</pre>
     *
     * @param callback a {@link BiConsumer} whose first argument is this context and
     *                 whose second argument is the new context that replaces this context
     */
    void onChild(BiConsumer<? super RequestContext, ? super RequestContext> callback);

    /**
     * Invokes all {@link #onEnter(Consumer)} callbacks. It is discouraged to use this method directly.
     * Use {@link #makeContextAware(Runnable)} or {@link #push(boolean)} instead so that the callbacks are
     * invoked automatically.
     */
    void invokeOnEnterCallbacks();

    /**
     * Invokes all {@link #onExit(Consumer)} callbacks. It is discouraged to use this method directly.
     * Use {@link #makeContextAware(Runnable)} or {@link #push(boolean)} instead so that the callbacks are
     * invoked automatically.
     */
    void invokeOnExitCallbacks();

    /**
     * Invokes all {@link #onChild(BiConsumer)} callbacks. It is discouraged to use this method directly.
     * Use {@link #makeContextAware(Runnable)} or {@link #push(boolean)} instead so that the callbacks are
     * invoked automatically.
     */
    void invokeOnChildCallbacks(RequestContext newCtx);

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
}
