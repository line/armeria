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

package com.linecorp.armeria.common;

import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.ResponseLog;
import com.linecorp.armeria.common.logging.ResponseLogBuilder;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.buffer.ByteBuf;
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
     * Pushes the specified context to the thread-local stack. To pop the context from the stack, call
     * {@link PushHandle#close()}, which can be done using a {@code try-finally} block:
     * <pre>{@code
     * try (PushHandler ignored = RequestContext.push(ctx)) {
     *     ...
     * }
     * }</pre>
     *
     * <p>The callbacks added by {@link #onEnter(Runnable)} and {@link #onExit(Runnable)} will be invoked
     * when the context is pushed to and removed from the thread-local stack respectively.
     *
     * <p>NOTE: In case of re-entrance, the callbacks will never run.
     */
    static PushHandle push(RequestContext ctx) {
        return push(ctx, true);
    }

    /**
     * Pushes the specified context to the thread-local stack. To pop the context from the stack, call
     * {@link PushHandle#close()}, which can be done using a {@code try-finally} block:
     * <pre>{@code
     * try (PushHandler ignored = RequestContext.push(ctx, true)) {
     *     ...
     * }
     * }</pre>
     *
     * <p>NOTE: This method is only useful when it is undesirable to invoke the callbacks, such as replacing
     *          the current context with another. Prefer {@link #push(RequestContext)} otherwise.
     *
     * @param runCallbacks if {@code true}, the callbacks added by {@link #onEnter(Runnable)} and
     *                     {@link #onExit(Runnable)} will be invoked when the context is pushed to and
     *                     removed from the thread-local stack respectively.
     *                     If {@code false}, no callbacks will be executed.
     *                     NOTE: In case of re-entrance, the callbacks will never run.
     */
    static PushHandle push(RequestContext ctx, boolean runCallbacks) {
        final RequestContext oldCtx = RequestContextThreadLocal.getAndSet(ctx);
        if (oldCtx == ctx) {
            // Reentrance
            return () -> {};
        }

        if (runCallbacks) {
            ctx.invokeOnEnterCallbacks();
            if (oldCtx != null) {
                return () -> {
                    ctx.invokeOnExitCallbacks();
                    RequestContextThreadLocal.set(oldCtx);
                };
            } else {
                return () -> {
                    ctx.invokeOnExitCallbacks();
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
     * Returns the {@link SessionProtocol} of the current {@link Request}.
     */
    SessionProtocol sessionProtocol();

    /**
     * Returns the session-layer method name of the current {@link Request}. e.g. "GET" or "POST" for HTTP
     */
    String method();

    /**
     * Returns the absolute path part of the current {@link Request}, as defined in
     * <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec5.html#sec5.1.2">the
     * section 5.1.2 of RFC2616</a>.
     */
    String path();

    /**
     * Returns the {@link Request} associated with this context.
     */
    <T> T request();

    /**
     * Returns the {@link RequestLogBuilder} that collects the information about the current {@link Request}.
     */
    RequestLogBuilder requestLogBuilder();

    /**
     * Returns the {@link ResponseLogBuilder} that collects the information about the current {@link Response}.
     */
    ResponseLogBuilder responseLogBuilder();

    /**
     * Returns the {@link CompletableFuture} that completes when the {@link #requestLogBuilder()} finished
     * collecting the {@link Request} information.
     */
    CompletableFuture<RequestLog> requestLogFuture();

    /**
     * Returns the {@link CompletableFuture} that completes when the {@link #responseLogBuilder()} finished
     * collecting the {@link Response} information.
     */
    CompletableFuture<ResponseLog> responseLogFuture();

    /**
     * Returns all {@link Attribute}s set in this context.
     */
    Iterator<Attribute<?>> attrs();

    /**
     * Returns the {@link EventLoop} that is handling the current {@link Request}.
     */
    EventLoop eventLoop();

    /**
     * Returns an {@link EventLoop} that will make sure this {@link RequestContext} is set as the current
     * context before executing any callback. This should almost always be used for executing asynchronous
     * callbacks in service code to make sure features that require the {@link RequestContext} work properly.
     * Most asynchronous libraries like {@link CompletableFuture} provide methods that accept an
     * {@link Executor} to run callbacks on.
     */
    default EventLoop contextAwareEventLoop() {
        return new RequestContextAwareEventLoop(this, eventLoop());
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
     * Returns a {@link FutureListener} that makes sure the current {@link RequestContext} is set and then
     * invokes the input {@code listener}.
     */
    @Deprecated
    <T> FutureListener<T> makeContextAware(FutureListener<T> listener);

    /**
     * Returns a {@link ChannelFutureListener} that makes sure the current {@link RequestContext} is set and
     * then invokes the input {@code listener}.
     */
    @Deprecated
    ChannelFutureListener makeContextAware(ChannelFutureListener listener);

    /**
     * Returns a {@link GenericFutureListener} that makes sure the current {@link RequestContext} is set and
     * then invokes the input {@code listener}.
     */
    @Deprecated
    <T extends Future<?>> GenericFutureListener<T> makeContextAware(GenericFutureListener<T> listener);

    /**
     * Registers {@code callback} to be run when re-entering this {@link RequestContext}, usually when using
     * the {@link #makeContextAware} family of methods. Any thread-local state associated with this context
     * should be restored by this callback.
     */
    void onEnter(Runnable callback);

    /**
     * Registers {@code callback} to be run when re-exiting this {@link RequestContext}, usually when using
     * the {@link #makeContextAware} family of methods. Any thread-local state associated with this context
     * should be reset by this callback.
     */
    void onExit(Runnable callback);

    /**
     * Invokes all {@link #onEnter(Runnable)} callbacks. It is discouraged to use this method directly.
     * Use {@link #makeContextAware(Runnable)} or {@link #push(RequestContext, boolean)} instead so that
     * the callbacks are invoked automatically.
     */
    void invokeOnEnterCallbacks();

    /**
     * Invokes all {@link #onExit(Runnable)} callbacks. It is discouraged to use this method directly.
     * Use {@link #makeContextAware(Runnable)} or {@link #push(RequestContext, boolean)} instead so that
     * the callbacks are invoked automatically.
     */
    void invokeOnExitCallbacks();

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
     * An {@link AutoCloseable} that automatically pops the context stack when it is closed.
     *
     * @see #push(RequestContext)
     */
    @FunctionalInterface
    interface PushHandle extends AutoCloseable {
        @Override
        void close();
    }
}
