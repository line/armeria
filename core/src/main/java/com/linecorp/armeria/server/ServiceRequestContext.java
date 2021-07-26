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
package com.linecorp.armeria.server;

import static com.linecorp.armeria.internal.common.RequestContextUtil.newIllegalContextPushingException;
import static com.linecorp.armeria.internal.common.RequestContextUtil.noopSafeCloseable;

import java.net.InetAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.errorprone.annotations.MustBeClosed;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.ContextAwareScheduledExecutorService;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.internal.common.RequestContextUtil;

/**
 * Provides information about an invocation and related utilities. Every request being handled has its own
 * {@link ServiceRequestContext} instance.
 */
public interface ServiceRequestContext extends RequestContext {

    /**
     * Returns the server-side context of the {@link Request} that is being handled in the current thread.
     * If the context is a {@link ClientRequestContext}, {@link ClientRequestContext#root()} is returned.
     *
     * @throws IllegalStateException if the context is unavailable in the current thread or
     *                               the current context is a {@link ClientRequestContext} and
     *                               {@link ClientRequestContext#root()} is {@code null}
     */
    static ServiceRequestContext current() {
        final RequestContext ctx = RequestContext.current();
        final ServiceRequestContext root = ctx.root();
        if (root != null) {
            return root;
        }

        throw new IllegalStateException(
                "The current context is not a server-side context and does not have a root " +
                "which means that the context is not invoked by a server request. ctx: " + ctx);
    }

    /**
     * Returns the server-side context of the {@link Request} that is being handled in the current thread.
     * If the context is a {@link ClientRequestContext}, {@link ClientRequestContext#root()} is returned.
     *
     * @return the {@link ServiceRequestContext} available in the current thread,
     *         or {@code null} if unavailable.
     */
    @Nullable
    static ServiceRequestContext currentOrNull() {
        final RequestContext ctx = RequestContext.currentOrNull();
        if (ctx == null) {
            return null;
        }

        return ctx.root();
    }

    /**
     * Maps the server-side context of the {@link Request} that is being handled in the current thread.
     *
     * @param mapper the {@link Function} that maps the {@link ServiceRequestContext}
     * @param defaultValueSupplier the {@link Supplier} that provides the value when the context is unavailable
     *                             in the current thread. If {@code null}, the {@code null} will be returned
     *                             when the context is unavailable in the current thread.
     * @throws IllegalStateException if the current context is not a {@link ServiceRequestContext}.
     */
    @Nullable
    static <T> T mapCurrent(
            Function<? super ServiceRequestContext, T> mapper, @Nullable Supplier<T> defaultValueSupplier) {

        final ServiceRequestContext ctx = currentOrNull();
        if (ctx != null) {
            return mapper.apply(ctx);
        }

        final ClientRequestContext clientRequestContext = ClientRequestContext.currentOrNull();
        if (clientRequestContext != null) {
            throw new IllegalStateException(
                    "The current context is not a server-side context and does not have a root " +
                    "which means that the context is not invoked by a server request. ctx: " +
                    clientRequestContext);
        }

        if (defaultValueSupplier != null) {
            return defaultValueSupplier.get();
        }

        return null;
    }

    /**
     * Returns a new {@link ServiceRequestContext} created from the specified {@link HttpRequest}.
     * Note that it is not usually required to create a new context by yourself, because Armeria
     * will always provide a context object for you. However, it may be useful in some cases such as
     * unit testing.
     *
     * @see ServiceRequestContextBuilder
     */
    static ServiceRequestContext of(HttpRequest request) {
        return builder(request).build();
    }

    /**
     * Returns a new {@link ServiceRequestContextBuilder} created from the specified {@link HttpRequest}.
     */
    static ServiceRequestContextBuilder builder(HttpRequest request) {
        return new ServiceRequestContextBuilder(request);
    }

    /**
     * {@inheritDoc} This method always returns {@code this}.
     *
     * @return {@code this}
     */
    @Nonnull
    @Override
    default ServiceRequestContext root() {
        return this;
    }

    /**
     * Returns the {@link HttpRequest} associated with this context.
     */
    @Nonnull
    @Override
    HttpRequest request();

    /**
     * {@inheritDoc} For example, this method will return {@code null} when the request being handled is
     * 1) not an RPC request or 2) not decoded into an RPC request yet.
     */
    @Nullable
    @Override
    RpcRequest rpcRequest();

    /**
     * Returns the remote address of this request.
     */
    @Nonnull
    @Override
    <A extends SocketAddress> A remoteAddress();

    /**
     * Returns the local address of this request.
     */
    @Nonnull
    @Override
    <A extends SocketAddress> A localAddress();

    /**
     * Returns the address of the client who initiated this request.
     */
    InetAddress clientAddress();

    /**
     * Pushes this context to the thread-local stack. To pop the context from the stack, call
     * {@link SafeCloseable#close()}, which can be done using a {@code try-with-resources} block:
     * <pre>{@code
     * try (SafeCloseable ignored = ctx.push()) {
     *     ...
     * }
     * }</pre>
     *
     * <p>In order to call this method, the current thread-local state must meet one of the
     * following conditions:
     * <ul>
     *   <li>the thread-local does not have any {@link RequestContext} in it</li>
     *   <li>the thread-local has the same {@link ServiceRequestContext} as this - reentrance</li>
     *   <li>the thread-local has the {@link ClientRequestContext} whose {@link ClientRequestContext#root()}
     *       is the same {@link ServiceRequestContext} as this</li>
     * </ul>
     * Otherwise, this method will throw an {@link IllegalStateException}.
     */
    @Override
    @MustBeClosed
    default SafeCloseable push() {
        final RequestContext oldCtx = RequestContextUtil.getAndSet(this);
        if (oldCtx == this) {
            // Reentrance
            return noopSafeCloseable();
        }

        if (oldCtx == null) {
            return () -> RequestContextUtil.pop(this, null);
        }

        if (oldCtx.root() == this) {
            return () -> RequestContextUtil.pop(this, oldCtx);
        }

        // Put the oldCtx back before throwing an exception.
        RequestContextUtil.pop(this, oldCtx);
        throw newIllegalContextPushingException(this, oldCtx);
    }

    /**
     * Returns the {@link ServiceConfig} of the {@link Service} that is handling the current {@link Request}.
     */
    ServiceConfig config();

    /**
     * Returns the {@link RoutingContext} used to find the {@link Service}.
     */
    RoutingContext routingContext();

    /**
     * Returns the path parameters mapped by the {@link Route} associated with the {@link Service}
     * that is handling the current {@link Request}.
     */
    Map<String, String> pathParams();

    /**
     * Returns the value of the specified path parameter.
     */
    @Nullable
    default String pathParam(String name) {
        return pathParams().get(name);
    }

    /**
     * Returns the {@link ContextAwareScheduledExecutorService} that could be used for executing
     * a potentially long-running task. The {@link ContextAwareScheduledExecutorService}
     * sets this {@link ServiceRequestContext} as the current context before executing any submitted tasks.
     * If you want to use {@link ScheduledExecutorService} without setting this context,
     * call {@link ContextAwareScheduledExecutorService#withoutContext()} and use the returned
     * {@link ScheduledExecutorService}.
     */
    ContextAwareScheduledExecutorService blockingTaskExecutor();

    /**
     * Returns the {@link #path()} with its context path removed. This method can be useful for a reusable
     * service bound at various path prefixes.
     */
    String mappedPath();

    /**
     * Returns the {@link #decodedPath()} with its context path removed. This method can be useful for
     * a reusable service bound at various path prefixes.
     */
    String decodedMappedPath();

    /**
     * Returns the negotiated producible media type. If the media type negotiation is not used for the
     * {@link Service}, {@code null} would be returned.
     */
    @Nullable
    MediaType negotiatedResponseMediaType();

    /**
     * Returns the amount of time allowed from the start time of the {@link Request} until receiving
     * the current {@link Request} and sending the corresponding {@link Response} completely.
     * This value is initially set from {@link ServiceConfig#requestTimeoutMillis()}.
     */
    long requestTimeoutMillis();

    /**
     * Clears the previously scheduled request timeout, if any.
     * Note that calling this will prevent the request from ever being timed out.
     */
    void clearRequestTimeout();

    /**
     * Schedules the request timeout that is triggered when the {@link Request} is not fully received or
     * the corresponding {@link Response} is not sent completely within the specified amount time from now.
     * Note that the specified {@code requestTimeout} must be positive.
     * This value is initially set from {@link ServiceConfig#requestTimeoutMillis()}.
     * This method is a shortcut for
     * {@code setRequestTimeoutMillis(TimeoutMode.SET_FROM_NOW, requestTimeoutMillis)}.
     *
     * <p>For example:
     * <pre>{@code
     * ServiceRequestContext ctx = ...;
     * // Schedules timeout after 1 seconds from now.
     * ctx.setRequestTimeoutMillis(1000);
     * }</pre>
     *
     * @param requestTimeoutMillis the amount of time allowed in milliseconds from now
     *
     */
    default void setRequestTimeoutMillis(long requestTimeoutMillis) {
        setRequestTimeoutMillis(TimeoutMode.SET_FROM_NOW, requestTimeoutMillis);
    }

    /**
     * Schedules the request timeout that is triggered when the {@link Request} is not fully received or
     * the corresponding {@link Response} is not sent completely within the specified {@link TimeoutMode}
     * and the specified {@code requestTimeoutMillis}.
     *
     * <table>
     * <caption>timeout mode description</caption>
     * <tr><th>Timeout mode</th><th>description</th></tr>
     * <tr><td>{@link TimeoutMode#SET_FROM_NOW}</td>
     *     <td>Sets a given amount of timeout from the current time.</td></tr>
     * <tr><td>{@link TimeoutMode#SET_FROM_START}</td>
     *     <td>Sets a given amount of timeout since the current {@link Request} began processing.</td></tr>
     * <tr><td>{@link TimeoutMode#EXTEND}</td>
     *     <td>Extends the previously scheduled timeout by the given amount of timeout.</td></tr>
     * </table>
     *
     * <p>For example:
     * <pre>{@code
     * ServiceRequestContext ctx = ...;
     * // Schedules a timeout from the start time of the request
     * ctx.setRequestTimeoutMillis(TimeoutMode.SET_FROM_START, 2000);
     * assert ctx.requestTimeoutMillis() == 2000;
     * ctx.setRequestTimeoutMillis(TimeoutMode.SET_FROM_START, 1000);
     * assert ctx.requestTimeoutMillis() == 1000;
     *
     * // Schedules timeout after 3 seconds from now.
     * ctx.setRequestTimeoutMillis(TimeoutMode.SET_FROM_NOW, 3000);
     *
     * // Extends the previously scheduled timeout.
     * long oldRequestTimeoutMillis = ctx.requestTimeoutMillis();
     * ctx.setRequestTimeoutMillis(TimeoutMode.EXTEND, 1000);
     * assert ctx.requestTimeoutMillis() == oldRequestTimeoutMillis + 1000;
     * ctx.extendRequestTimeoutMillis(TimeoutMode.EXTEND, -500);
     * assert ctx.requestTimeoutMillis() == oldRequestTimeoutMillis + 500;
     * }</pre>
     */
    void setRequestTimeoutMillis(TimeoutMode mode, long requestTimeoutMillis);

    /**
     * Schedules the request timeout that is triggered when the {@link Request} is not fully received or
     * the corresponding {@link Response} is not sent completely within the specified amount time from now.
     * Note that the specified {@code requestTimeout} must be positive.
     * This value is initially set from {@link ServiceConfig#requestTimeoutMillis()}.
     * This method is a shortcut for {@code setRequestTimeout(TimeoutMode.SET_FROM_NOW, requestTimeout)}.
     *
     * <p>For example:
     * <pre>{@code
     * ServiceRequestContext ctx = ...;
     * // Schedules timeout after 1 seconds from now.
     * ctx.setRequestTimeout(Duration.ofSeconds(1));
     * }</pre>
     *
     * @param requestTimeout the amount of time allowed from now
     *
     */
    default void setRequestTimeout(Duration requestTimeout) {
        setRequestTimeout(TimeoutMode.SET_FROM_NOW, requestTimeout);
    }

    /**
     * Schedules the request timeout that is triggered when the {@link Request} is not fully received or
     * the corresponding {@link Response} is not sent completely within the specified {@link TimeoutMode}
     * and the specified {@code requestTimeout}.
     *
     * <table>
     * <caption>timeout mode description</caption>
     * <tr><th>Timeout mode</th><th>description</th></tr>
     * <tr><td>{@link TimeoutMode#SET_FROM_NOW}</td>
     *     <td>Sets a given amount of timeout from the current time.</td></tr>
     * <tr><td>{@link TimeoutMode#SET_FROM_START}</td>
     *     <td>Sets a given amount of timeout since the current {@link Request} began processing.</td></tr>
     * <tr><td>{@link TimeoutMode#EXTEND}</td>
     *     <td>Extends the previously scheduled timeout by the given amount of timeout.</td></tr>
     * </table>
     *
     * <p>For example:
     * <pre>{@code
     * ServiceRequestContext ctx = ...;
     * // Schedules a timeout from the start time of the request
     * ctx.setRequestTimeout(TimeoutMode.SET_FROM_START, Duration.ofSeconds(2));
     * assert ctx.requestTimeoutMillis() == 2000;
     * ctx.setRequestTimeout(TimeoutMode.SET_FROM_START, Duration.ofSeconds(1));
     * assert ctx.requestTimeoutMillis() == 1000;
     *
     * // Schedules timeout after 3 seconds from now.
     * ctx.setRequestTimeout(TimeoutMode.SET_FROM_NOW, Duration.ofSeconds(3));
     *
     * // Extends the previously scheduled timeout.
     * long oldRequestTimeoutMillis = ctx.requestTimeoutMillis();
     * ctx.setRequestTimeout(TimeoutMode.EXTEND, Duration.ofSeconds(1));
     * assert ctx.requestTimeoutMillis() == oldRequestTimeoutMillis + 1000;
     * ctx.setRequestTimeout(TimeoutMode.EXTEND, Duration.ofMillis(-500));
     * assert ctx.requestTimeoutMillis() == oldRequestTimeoutMillis + 500;
     * }</pre>
     */
    void setRequestTimeout(TimeoutMode mode, Duration requestTimeout);

    /**
     * Returns a {@link CompletableFuture} which is completed with a {@link Throwable} cancellation cause when
     * the {@link ServiceRequestContext} is about to get cancelled. If the request is handled successfully
     * without cancellation, the {@link CompletableFuture} won't complete.
     */
    CompletableFuture<Throwable> whenRequestCancelling();

    /**
     * Returns a {@link CompletableFuture} which is completed with a {@link Throwable} cancellation cause after
     * the {@link ServiceRequestContext} has been cancelled. {@link #isCancelled()} will always return
     * {@code true} when the returned {@link CompletableFuture} is completed. If the request is handled
     * successfully without cancellation, the {@link CompletableFuture} won't complete.
     */
    CompletableFuture<Throwable> whenRequestCancelled();

    /**
     * Returns a {@link CompletableFuture} which is completed when the {@link ServiceRequestContext} is about
     * to get timed out. If the request is handled successfully or not cancelled by timeout, the
     * {@link CompletableFuture} won't complete.
     *
     * @deprecated Use {@link #whenRequestCancelling()} instead.
     */
    @Deprecated
    CompletableFuture<Void> whenRequestTimingOut();

    /**
     * Returns a {@link CompletableFuture} which is completed after the {@link ServiceRequestContext} has been
     * timed out. {@link #isTimedOut()} will always return {@code true} when the returned
     * {@link CompletableFuture} is completed. If the request is handled successfully or not cancelled by
     * timeout, the {@link CompletableFuture} won't complete.
     *
     * @deprecated Use {@link #whenRequestCancelled()} instead.
     */
    @Deprecated
    CompletableFuture<Void> whenRequestTimedOut();

    /**
     * Cancels the request. Shortcut for {@code cancel(RequestCancellationException.get())}.
     */
    @Override
    default void cancel() {
        cancel(RequestCancellationException.get());
    }

    /**
     * Times out the request. Shortcut for {@code cancel(RequestTimeoutException.get())}.
     */
    @Override
    default void timeoutNow() {
        cancel(RequestTimeoutException.get());
    }

    /**
     * Returns the maximum length of the current {@link Request}.
     * This value is initially set from {@link ServiceConfig#maxRequestLength()}.
     * If 0, there is no limit on the request size.
     *
     * @see ContentTooLargeException
     */
    long maxRequestLength();

    /**
     * Sets the maximum length of the current {@link Request}.
     * This value is initially set from {@link ServiceConfig#maxRequestLength()}.
     * If 0, there is no limit on the request size.
     *
     * @see ContentTooLargeException
     */
    void setMaxRequestLength(long maxRequestLength);

    /**
     * Returns the {@link HttpHeaders} which will be included when a {@link Service} sends an
     * {@link HttpResponse}.
     */
    HttpHeaders additionalResponseHeaders();

    /**
     * Sets a header with the specified {@code name} and {@code value}. This will remove all previous values
     * associated with the specified {@code name}.
     * The header will be included when a {@link Service} sends an {@link HttpResponse}.
     */
    void setAdditionalResponseHeader(CharSequence name, Object value);

    /**
     * Adds a header with the specified {@code name} and {@code value}. The header will be included when
     * a {@link Service} sends an {@link HttpResponse}.
     */
    void addAdditionalResponseHeader(CharSequence name, Object value);

    /**
     * Mutates the {@link HttpHeaders} which will be included when a {@link Service} sends an
     * {@link HttpResponse}.
     *
     * @param mutator the {@link Consumer} that mutates the additional response headers
     */
    void mutateAdditionalResponseHeaders(Consumer<HttpHeadersBuilder> mutator);

    /**
     * Returns the {@link HttpHeaders} which is included along with any other trailers when a
     * {@link Service} completes an {@link HttpResponse}.
     */
    HttpHeaders additionalResponseTrailers();

    /**
     * Sets a trailer with the specified {@code name} and {@code value}. This will remove all previous values
     * associated with the specified {@code name}.
     * The trailer will be included when a {@link Service} completes an {@link HttpResponse}.
     */
    void setAdditionalResponseTrailer(CharSequence name, Object value);

    /**
     * Adds a trailer with the specified {@code name} and {@code value}. The trailer will be included when
     * a {@link Service} completes an {@link HttpResponse}.
     */
    void addAdditionalResponseTrailer(CharSequence name, Object value);

    /**
     * Mutates the {@link HttpHeaders} which is included along with any other trailers when a
     * {@link Service} completes an {@link HttpResponse}.
     *
     * @param mutator the {@link Consumer} that mutates the additional trailers
     */
    void mutateAdditionalResponseTrailers(Consumer<HttpHeadersBuilder> mutator);

    /**
     * Returns the proxied addresses of the current {@link Request}.
     */
    ProxiedAddresses proxiedAddresses();

    /**
     * Initiates connection shutdown with a given grace period.
     * New requests are still accepted during the grace period. If grace period is zero or negative - initiates
     * connection shutdown and stops accepting incoming requests immediately.
     * If graceful shutdown is already triggered and given grace period is smaller than the wait time before
     * the grace period end - reschedules grace period end to happen faster. Otherwise, grace period will
     * end as it was previously scheduled.
     * Returns {@link CompletableFuture} that completes when the channel is closed.
     */
    @UnstableApi
    CompletableFuture<Void> initiateConnectionShutdown(Duration gracePeriod);

    /**
     * Initiates connection shutdown without overriding current configuration of the grace period.
     * See {@link ServiceRequestContext#initiateConnectionShutdown(Duration)} for a version that
     * takes grace period as an input.
     * Returns {@link CompletableFuture} that completes when the channel is closed.
     */
    @UnstableApi
    CompletableFuture<Void> initiateConnectionShutdown();
}
