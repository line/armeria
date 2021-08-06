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

package com.linecorp.armeria.client;

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.internal.common.RequestContextUtil.newIllegalContextPushingException;
import static com.linecorp.armeria.internal.common.RequestContextUtil.noopSafeCloseable;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.errorprone.annotations.MustBeClosed;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.internal.common.RequestContextUtil;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.Attribute;

/**
 * Provides information about a {@link Request}, its {@link Response} and its related utilities.
 * Every client request has its own {@link ClientRequestContext} instance.
 */
public interface ClientRequestContext extends RequestContext {

    /**
     * Returns the client-side context of the {@link Request} that is being handled in the current thread.
     *
     * @throws IllegalStateException if the context is unavailable in the current thread or
     *                               the current context is not a {@link ClientRequestContext}.
     */
    static ClientRequestContext current() {
        final RequestContext ctx = RequestContext.current();
        checkState(ctx instanceof ClientRequestContext,
                   "The current context is not a client-side context: %s", ctx);
        return (ClientRequestContext) ctx;
    }

    /**
     * Returns the client-side context of the {@link Request} that is being handled in the current thread.
     *
     * @return the {@link ClientRequestContext} available in the current thread, or {@code null} if unavailable.
     */
    @Nullable
    static ClientRequestContext currentOrNull() {
        final RequestContext ctx = RequestContext.currentOrNull();
        if (ctx instanceof ClientRequestContext) {
            return (ClientRequestContext) ctx;
        }
        return null;
    }

    /**
     * Maps the client-side context of the {@link Request} that is being handled in the current thread.
     *
     * @param mapper the {@link Function} that maps the {@link ClientRequestContext}
     * @param defaultValueSupplier the {@link Supplier} that provides the value when the context is unavailable
     *                             in the current thread. If {@code null}, the {@code null} will be returned
     *                             when the context is unavailable in the current thread.
     * @throws IllegalStateException if the current context is not a {@link ClientRequestContext}.
     */
    @Nullable
    static <T> T mapCurrent(
            Function<? super ClientRequestContext, T> mapper, @Nullable Supplier<T> defaultValueSupplier) {

        final ClientRequestContext ctx = currentOrNull();
        if (ctx != null) {
            return mapper.apply(ctx);
        }

        final ServiceRequestContext serviceRequestContext = ServiceRequestContext.currentOrNull();
        if (serviceRequestContext != null) {
            throw new IllegalStateException("The current context is not a client-side context: " +
                                            serviceRequestContext);
        }

        if (defaultValueSupplier != null) {
            return defaultValueSupplier.get();
        }

        return null;
    }

    /**
     * Returns a new {@link ClientRequestContext} created from the specified {@link HttpRequest}.
     * Note that it is not usually required to create a new context by yourself, because Armeria
     * will always provide a context object for you. However, it may be useful in some cases such as
     * unit testing.
     *
     * @see ClientRequestContextBuilder
     */
    static ClientRequestContext of(HttpRequest request) {
        return builder(request).build();
    }

    /**
     * Returns a new {@link ClientRequestContext} created from the specified {@link RpcRequest} and URI.
     * Note that it is not usually required to create a new context by yourself, because Armeria
     * will always provide a context object for you. However, it may be useful in some cases such as
     * unit testing.
     *
     * @see ClientRequestContextBuilder
     */
    static ClientRequestContext of(RpcRequest request, String uri) {
        return builder(request, URI.create(requireNonNull(uri, "uri"))).build();
    }

    /**
     * Returns a new {@link ClientRequestContext} created from the specified {@link RpcRequest} and {@link URI}.
     * Note that it is not usually required to create a new context by yourself, because Armeria
     * will always provide a context object for you. However, it may be useful in some cases such as
     * unit testing.
     *
     * @see ClientRequestContextBuilder
     */
    static ClientRequestContext of(RpcRequest request, URI uri) {
        return builder(request, uri).build();
    }

    /**
     * Returns a new {@link ClientRequestContextBuilder} created from the specified {@link HttpRequest}.
     */
    static ClientRequestContextBuilder builder(HttpRequest request) {
        return new ClientRequestContextBuilder(request);
    }

    /**
     * Returns a new {@link ClientRequestContextBuilder} created from the specified {@link RpcRequest} and
     * {@code uri}.
     */
    static ClientRequestContextBuilder builder(RpcRequest request, String uri) {
        return builder(request, URI.create(requireNonNull(uri, "uri")));
    }

    /**
     * Returns a new {@link ClientRequestContextBuilder} created from the specified {@link RpcRequest} and
     * {@link URI}.
     */
    static ClientRequestContextBuilder builder(RpcRequest request, URI uri) {
        return new ClientRequestContextBuilder(request, uri);
    }

    /**
     * {@inheritDoc} For example, when you send an RPC request, this method will return {@code null} until
     * the RPC request is translated into an HTTP request.
     */
    @Nullable
    @Override
    HttpRequest request();

    /**
     * {@inheritDoc} For example, this method will return {@code null} when you are not sending an RPC request
     * but just a plain HTTP request.
     */
    @Nullable
    @Override
    RpcRequest rpcRequest();

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
     *   <li>the thread-local has the same {@link ClientRequestContext} as this - reentrance</li>
     *   <li>the thread-local has the {@link ServiceRequestContext} which is the same as {@link #root()}</li>
     *   <li>the thread-local has the {@link ClientRequestContext} whose {@link #root()}
     *       is the same {@link #root()}</li>
     *   <li>the thread-local has the {@link ClientRequestContext} whose {@link #root()} is {@code null}
     *       and this {@link #root()} is {@code null}</li>
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
            return RequestContextUtil.invokeHookAndPop(this, null);
        }

        final ServiceRequestContext root = root();
        if (oldCtx.root() == root) {
            return RequestContextUtil.invokeHookAndPop(this, oldCtx);
        }

        // Put the oldCtx back before throwing an exception.
        RequestContextUtil.pop(this, oldCtx);
        throw newIllegalContextPushingException(this, oldCtx);
    }

    /**
     * Creates a new {@link ClientRequestContext} whose properties and {@link Attribute}s are copied from this
     * {@link ClientRequestContext}, except having different {@link Request}, {@link Endpoint} and its own
     * {@link RequestLog}.
     *
     * <p>Note that this method does not copy the {@link RequestLog} properties to the derived context.
     */
    ClientRequestContext newDerivedContext(RequestId id, @Nullable HttpRequest req, @Nullable RpcRequest rpcReq,
                                           @Nullable Endpoint endpoint);

    /**
     * Returns the {@link EndpointGroup} used for the current {@link Request}.
     *
     * @return the {@link EndpointGroup} if a user specified an {@link EndpointGroup} when initiating
     *         a {@link Request}. {@code null} if a user specified an {@link Endpoint}.
     */
    @Nullable
    EndpointGroup endpointGroup();

    /**
     * Returns the remote {@link Endpoint} of the current {@link Request}.
     *
     * @return the remote {@link Endpoint}, or {@code null} if the {@link Request} has failed
     *         because its remote {@link Endpoint} couldn't be determined.
     */
    @Nullable
    Endpoint endpoint();

    /**
     * Returns the {@link ClientOptions} of the current {@link Request}.
     */
    ClientOptions options();

    /**
     * Returns the fragment part of the URI of the current {@link Request}, as defined in
     * <a href="https://datatracker.ietf.org/doc/html/rfc3986#section-3.5">the section 3.5 of RFC3986</a>.
     *
     * @return the fragment part of the request URI, or {@code null} if no fragment was specified
     */
    @Nullable
    String fragment();

    /**
     * Returns the amount of time allowed until the initial write attempt of the current {@link Request}
     * succeeds. This value is initially set from {@link ClientOptions#WRITE_TIMEOUT_MILLIS}.
     */
    long writeTimeoutMillis();

    /**
     * Sets the amount of time allowed until the initial write attempt of the current {@link Request}
     * succeeds. This value is initially set from {@link ClientOptions#WRITE_TIMEOUT_MILLIS}.
     */
    void setWriteTimeoutMillis(long writeTimeoutMillis);

    /**
     * Sets the amount of time allowed until the initial write attempt of the current {@link Request}
     * succeeds. This value is initially set from {@link ClientOptions#WRITE_TIMEOUT_MILLIS}.
     */
    void setWriteTimeout(Duration writeTimeout);

    /**
     * Returns the amount of time allowed until receiving the {@link Response} completely
     * since the transfer of the {@link Response} started or the {@link Request} was fully sent. This value is
     * initially set from {@link ClientOptions#RESPONSE_TIMEOUT_MILLIS}.
     */
    long responseTimeoutMillis();

    /**
     * Clears the previously scheduled response timeout, if any.
     * Note that calling this will prevent the response from ever being timed out.
     */
    void clearResponseTimeout();

    /**
     * Schedules the response timeout that is triggered when the {@link Response} is not fully received within
     * the specified {@link TimeoutMode} and the specified {@code responseTimeoutMillis} since
     * the {@link Response} started or {@link Request} was fully sent.
     * This value is initially set from {@link ClientOptions#RESPONSE_TIMEOUT_MILLIS}.
     *
     * <table>
     * <caption>timeout mode description</caption>
     * <tr><th>Timeout mode</th><th>description</th></tr>
     * <tr><td>{@link TimeoutMode#SET_FROM_NOW}</td>
     *     <td>Sets a given amount of timeout from the current time.</td></tr>
     * <tr><td>{@link TimeoutMode#SET_FROM_START}</td>
     *     <td>Sets a given amount of timeout since the current {@link Response} began processing.</td></tr>
     * <tr><td>{@link TimeoutMode#EXTEND}</td>
     *     <td>Extends the previously scheduled timeout by the given amount of timeout.</td></tr>
     * </table>
     *
     * <p>For example:
     * <pre>{@code
     * ClientRequestContext ctx = ...;
     * // Schedules a timeout from the start time of the response
     * ctx.setResponseTimeoutMillis(TimeoutMode.SET_FROM_START, 2000);
     * assert ctx.responseTimeoutMillis() == 2000;
     * ctx.setResponseTimeoutMillis(TimeoutMode.SET_FROM_START, 1000);
     * assert ctx.responseTimeoutMillis() == 1000;
     *
     * // Schedules timeout after 3 seconds from now.
     * ctx.setResponseTimeoutMillis(TimeoutMode.SET_FROM_NOW, 3000);
     *
     * // Extends the previously scheduled timeout.
     * long oldResponseTimeoutMillis = ctx.responseTimeoutMillis();
     * ctx.setResponseTimeoutMillis(TimeoutMode.EXTEND, 1000);
     * assert ctx.responseTimeoutMillis() == oldResponseTimeoutMillis + 1000;
     * ctx.extendResponseTimeoutMillis(TimeoutMode.EXTEND, -500);
     * assert ctx.responseTimeoutMillis() == oldResponseTimeoutMillis + 500;
     * }</pre>
     */
    void setResponseTimeoutMillis(TimeoutMode mode, long responseTimeoutMillis);

    /**
     * Schedules the response timeout that is triggered when the {@link Response} is not
     * fully received within the specified amount of time from now.
     * Note that the specified {@code responseTimeoutMillis} must be positive.
     * This value is initially set from {@link ClientOptions#RESPONSE_TIMEOUT_MILLIS}.
     * This method is a shortcut for
     * {@code setResponseTimeoutMillis(TimeoutMode.SET_FROM_NOW, responseTimeoutMillis)}.
     *
     * <p>For example:
     * <pre>{@code
     * ClientRequestContext ctx = ...;
     * // Schedules timeout after 1 seconds from now.
     * ctx.setResponseTimeoutMillis(1000);
     * }</pre>
     *
     * @param responseTimeoutMillis the amount of time allowed in milliseconds from now
     */
    default void setResponseTimeoutMillis(long responseTimeoutMillis) {
        setResponseTimeoutMillis(TimeoutMode.SET_FROM_NOW, responseTimeoutMillis);
    }

    /**
     * Schedules the response timeout that is triggered when the {@link Response} is not fully received within
     * the specified {@link TimeoutMode} and the specified {@code responseTimeoutMillis} since
     * the {@link Response} started or {@link Request} was fully sent.
     * This value is initially set from {@link ClientOptions#RESPONSE_TIMEOUT_MILLIS}.
     *
     * <table>
     * <caption>timeout mode description</caption>
     * <tr><th>Timeout mode</th><th>description</th></tr>
     * <tr><td>{@link TimeoutMode#SET_FROM_NOW}</td>
     *     <td>Sets a given amount of timeout from the current time.</td></tr>
     * <tr><td>{@link TimeoutMode#SET_FROM_START}</td>
     *     <td>Sets a given amount of timeout since the current {@link Response} began processing.</td></tr>
     * <tr><td>{@link TimeoutMode#EXTEND}</td>
     *     <td>Extends the previously scheduled timeout by the given amount of timeout.</td></tr>
     * </table>
     *
     * <p>For example:
     * <pre>{@code
     * ClientRequestContext ctx = ...;
     * // Schedules a timeout from the start time of the response
     * ctx.setResponseTimeoutMillis(TimeoutMode.SET_FROM_START, Duration.ofSeconds(2));
     * assert ctx.responseTimeoutMillis() == 2000;
     * ctx.setResponseTimeoutMillis(TimeoutMode.SET_FROM_START, Duration.ofSeconds(1));
     * assert ctx.responseTimeoutMillis() == 1000;
     *
     * // Schedules timeout after 3 seconds from now.
     * ctx.setResponseTimeoutMillis(TimeoutMode.SET_FROM_NOW, Duration.ofSeconds(3));
     *
     * // Extends the previously scheduled timeout.
     * long oldResponseTimeoutMillis = ctx.responseTimeoutMillis();
     * ctx.setResponseTimeoutMillis(TimeoutMode.EXTEND, Duration.ofSeconds(1));
     * assert ctx.responseTimeoutMillis() == oldResponseTimeoutMillis + 1000;
     * ctx.setResponseTimeoutMillis(TimeoutMode.EXTEND, Duration.ofMillis(-500));
     * assert ctx.responseTimeoutMillis() == oldResponseTimeoutMillis + 500;
     * }</pre>
     */
    void setResponseTimeout(TimeoutMode mode, Duration responseTimeout);

    /**
     * Schedules the response timeout that is triggered when the {@link Response} is not
     * fully received within the specified amount of time from now.
     * Note that the specified {@code responseTimeout} must be positive.
     * This value is initially set from {@link ClientOptions#RESPONSE_TIMEOUT_MILLIS}.
     * This method is a shortcut for {@code setResponseTimeout(TimeoutMode.SET_FROM_NOW, responseTimeout)}.
     *
     * <p>For example:
     * <pre>{@code
     * ClientRequestContext ctx = ...;
     * // Schedules timeout after 1 seconds from now.
     * ctx.setResponseTimeout(Duration.ofSeconds(1));
     * }</pre>
     *
     * @param responseTimeout the amount of time allowed from now
     *
     */
    default void setResponseTimeout(Duration responseTimeout) {
        setResponseTimeout(TimeoutMode.SET_FROM_NOW, responseTimeout);
    }

    /**
     * Returns a {@link CompletableFuture} which is completed with a {@link Throwable} cancellation cause when
     * the {@link ClientRequestContext} is about to get cancelled. If the response is handled successfully
     * without cancellation, the {@link CompletableFuture} won't complete.
     */
    CompletableFuture<Throwable> whenResponseCancelling();

    /**
     * Returns a {@link CompletableFuture} which is completed with a {@link Throwable} cancellation cause after
     * the {@link ClientRequestContext} has been cancelled. {@link #isCancelled()} will always return
     * {@code true} when the returned {@link CompletableFuture} is completed. If the response is handled
     * successfully without cancellation, the {@link CompletableFuture} won't complete.
     */
    CompletableFuture<Throwable> whenResponseCancelled();

    /**
     * Returns a {@link CompletableFuture} which is completed when the {@link ClientRequestContext} is about to
     * get timed out. If the response is handled successfully or not cancelled by timeout, the
     * {@link CompletableFuture} won't complete.
     *
     * @deprecated Use {@link #whenResponseCancelling()} instead.
     */
    @Deprecated
    CompletableFuture<Void> whenResponseTimingOut();

    /**
     * Returns a {@link CompletableFuture} which is completed after the {@link ClientRequestContext} has been
     * timed out. {@link #isTimedOut()} will always return {@code true} when the returned
     * {@link CompletableFuture} is completed. If the response is handled successfully or not cancelled by
     * timeout, the {@link CompletableFuture} won't complete.
     *
     * @deprecated Use {@link #whenResponseCancelled()} instead.
     */
    @Deprecated
    CompletableFuture<Void> whenResponseTimedOut();

    /**
     * Cancels the response. Shortcut for {@code cancel(ResponseCancellationException.get())}.
     */
    @Override
    default void cancel() {
        cancel(ResponseCancellationException.get());
    }

    /**
     * Times out the response. Shortcut for {@code cancel(ResponseTimeoutException.get())}.
     */
    @Override
    default void timeoutNow() {
        cancel(ResponseTimeoutException.get());
    }

    /**
     * Returns the maximum length of the received {@link Response}.
     * This value is initially set from {@link ClientOptions#MAX_RESPONSE_LENGTH}.
     *
     * @return the maximum length of the response. {@code 0} if unlimited.
     *
     * @see ContentTooLargeException
     */
    long maxResponseLength();

    /**
     * Sets the maximum length of the received {@link Response}.
     * This value is initially set from {@link ClientOptions#MAX_RESPONSE_LENGTH}.
     * Specify {@code 0} to disable the limit of the length of a response.
     *
     * @see ContentTooLargeException
     */
    void setMaxResponseLength(long maxResponseLength);

    /**
     * Returns an {@link HttpHeaders} which will be included when a {@link Client} sends an {@link HttpRequest}.
     */
    HttpHeaders additionalRequestHeaders();

    /**
     * Sets a header with the specified {@code name} and {@code value}. This will remove all previous values
     * associated with the specified {@code name}.
     * The header will be included when a {@link Client} sends an {@link HttpRequest}.
     */
    void setAdditionalRequestHeader(CharSequence name, Object value);

    /**
     * Adds a header with the specified {@code name} and {@code value}. The header will be included when
     * a {@link Client} sends an {@link HttpRequest}.
     */
    void addAdditionalRequestHeader(CharSequence name, Object value);

    /**
     * Mutates the {@link HttpHeaders} which will be included when a {@link Client} sends
     * an {@link HttpRequest}.
     *
     * @param mutator the {@link Consumer} that mutates the additional request headers
     */
    void mutateAdditionalRequestHeaders(Consumer<HttpHeadersBuilder> mutator);
}
