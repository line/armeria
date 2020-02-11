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
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.RequestContextThreadLocal;
import com.linecorp.armeria.server.logging.AccessLogWriter;

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
        if (ctx instanceof ServiceRequestContext) {
            return (ServiceRequestContext) ctx;
        }

        final ServiceRequestContext root = ((ClientRequestContext) ctx).root();
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

        if (ctx instanceof ServiceRequestContext) {
            return (ServiceRequestContext) ctx;
        }

        final ServiceRequestContext root = ((ClientRequestContext) ctx).root();
        if (root != null) {
            return root;
        }

        throw new IllegalStateException(
                "The current context is not a server-side context and does not have a root " +
                "which means that the context is not invoked by a server request. ctx: " + ctx);
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
    default InetAddress clientAddress() {
        final InetSocketAddress remoteAddress = remoteAddress();
        return remoteAddress.getAddress();
    }

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
    default SafeCloseable push() {
        final RequestContext oldCtx = RequestContextThreadLocal.getAndSet(this);
        if (oldCtx == this) {
            // Reentrance
            return noopSafeCloseable();
        }

        if (oldCtx instanceof ClientRequestContext && ((ClientRequestContext) oldCtx).root() == this) {
            return () -> RequestContextThreadLocal.set(oldCtx);
        }

        if (oldCtx == null) {
            return RequestContextThreadLocal::remove;
        }

        // Put the oldCtx back before throwing an exception.
        RequestContextThreadLocal.set(oldCtx);
        throw newIllegalContextPushingException(this, oldCtx);
    }

    @Override
    ServiceRequestContext newDerivedContext(RequestId id,
                                            @Nullable HttpRequest req,
                                            @Nullable RpcRequest rpcReq);

    /**
     * Returns the {@link Server} that is handling the current {@link Request}.
     */
    Server server();

    /**
     * Returns the {@link VirtualHost} that is handling the current {@link Request}.
     */
    VirtualHost virtualHost();

    /**
     * Returns the {@link Route} associated with the {@link Service} that is handling the current
     * {@link Request}.
     */
    Route route();

    /**
     * Returns the {@link RoutingContext} used to find the {@link Service}.
     */
    RoutingContext routingContext();

    /**
     * Returns the path parameters mapped by the {@link #route()} associated with the {@link Service}
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
     * Returns the {@link HttpService} that is handling the current {@link Request}.
     */
    HttpService service();

    /**
     * Returns the {@link ScheduledExecutorService} that could be used for executing a potentially
     * long-running task. The {@link ScheduledExecutorService} will propagate the {@link ServiceRequestContext}
     * automatically when running a task.
     *
     * <p>Note that performing a long-running task in {@link Service#serve(ServiceRequestContext, Request)}
     * may block the {@link Server}'s I/O event loop and thus should be executed in other threads.
     */
    ScheduledExecutorService blockingTaskExecutor();

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
     * Returns the negotiated producible media type. If the media type negotiation is not used for the
     * {@link Service}, {@code null} would be returned.
     *
     * @deprecated Use {@link #negotiatedResponseMediaType()}.
     */
    @Deprecated
    @Nullable
    default MediaType negotiatedProduceType() {
        return negotiatedResponseMediaType();
    }

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
     * the corresponding {@link Response} is not sent completely since the {@link Request} started.
     * This value is initially set from {@link ServiceConfig#requestTimeoutMillis()}.
     *
     * <p>For example:
     * <pre>{@code
     * ServiceRequestContext ctx = ...;
     * ctx.setRequestTimeoutMillis(1000);
     * assert ctx.requestTimeoutMillis() == 1000;
     * ctx.setRequestTimeoutMillis(2000);
     * assert ctx.requestTimeoutMillis() == 2000;
     * }</pre>
     *
     * @param requestTimeoutMillis the amount of time in milliseconds from the start time of the request
     *
     * @deprecated Use {@link #extendRequestTimeoutMillis(long)})}, {@link #setRequestTimeoutAfterMillis(long)},
     *                 {@link #setRequestTimeoutAtMillis(long)} or {@link #clearRequestTimeout()}
     */
    @Deprecated
    void setRequestTimeoutMillis(long requestTimeoutMillis);

    /**
     * Schedules the request timeout that is triggered when the {@link Request} is not fully received or
     * the corresponding {@link Response} is not sent completely since the {@link Request} started.
     * This value is initially set from {@link ServiceConfig#requestTimeoutMillis()}.
     *
     * <p>For example:
     * <pre>{@code
     * ServiceRequestContext ctx = ...;
     * ctx.setRequestTimeout(Duration.ofSeconds(1));
     * assert ctx.requestTimeoutMillis() == 1000;
     * ctx.setRequestTimeout(Duration.ofSeconds(2));
     * assert ctx.requestTimeoutMillis() == 2000;
     * }</pre>
     *
     * @param requestTimeout the amount of time from the start time of the request
     *
     * @deprecated Use {@link #extendRequestTimeout(Duration)}, {@link #setRequestTimeoutAfter(Duration)},
     *             {@link #setRequestTimeoutAt(Instant)} or {@link #clearRequestTimeout()}
     */
    @Deprecated
    void setRequestTimeout(Duration requestTimeout);

    /**
     * Extends the previously scheduled request timeout by the specified amount of {@code adjustmentMillis}.
     * This method does nothing if no request timeout was scheduled previously.
     * Note that a negative {@code adjustment} reduces the current timeout.
     * The initial timeout is set from {@link ServiceConfig#requestTimeoutMillis()}.
     *
     * <p>For example:
     * <pre>{@code
     * ServiceRequestContext ctx = ...;
     * long oldRequestTimeoutMillis = ctx.requestTimeoutMillis();
     * ctx.extendRequestTimeoutMillis(1000);
     * assert ctx.requestTimeoutMillis() == oldRequestTimeoutMillis + 1000;
     * ctx.extendRequestTimeoutMillis(-500);
     * assert ctx.requestTimeoutMillis() == oldRequestTimeoutMillis + 500;
     * }</pre>
     *
     * @param adjustmentMillis the amount of time in milliseconds to extend the current timeout by
     */
    void extendRequestTimeoutMillis(long adjustmentMillis);

    /**
     * Extends the previously scheduled request timeout by the specified amount of {@code adjustment}.
     * This method does nothing if no response timeout was scheduled previously.
     * Note that a negative {@code adjustment} reduces the current timeout.
     * The initial timeout is set from {@link ServiceConfig#requestTimeoutMillis()}.
     *
     * <p>For example:
     * <pre>{@code
     * ServiceRequestContext ctx = ...;
     * long oldRequestTimeoutMillis = ctx.requestTimeoutMillis();
     * ctx.extendRequestTimeout(Duration.ofSeconds(1));
     * assert ctx.requestTimeoutMillis() == oldRequestTimeoutMillis + 1000;
     * ctx.extendRequestTimeout(Duration.ofMillis(-500));
     * assert ctx.requestTimeoutMillis() == oldRequestTimeoutMillis + 500;
     * }</pre>
     *
     * @param adjustment the amount of time to extend the current timeout by
     */
    void extendRequestTimeout(Duration adjustment);

    /**
     * Schedules the request timeout that is triggered when the {@link Request} is not fully received or
     * the corresponding {@link Response} is not sent completely within the specified amount of time from now.
     * Note that the specified {@code requestTimeoutMillis} must be positive.
     * The initial timeout is set from {@link ServiceConfig#requestTimeoutMillis()}.
     *
     * <p>For example:
     * <pre>{@code
     * ServiceRequestContext ctx = ...;
     * // Schedules timeout after 1 seconds from now.
     * ctx.setRequestTimeoutAfterMillis(1000);
     * }</pre>
     *
     * @param requestTimeoutMillis the amount of time allowed in milliseconds from now
     */
    void setRequestTimeoutAfterMillis(long requestTimeoutMillis);

    /**
     * Schedules the request timeout that is triggered when the {@link Request} is not fully received or
     * the corresponding {@link Response} is not sent completely within the specified amount time from now.
     * Note that the specified {@code requestTimeout} must be positive.
     * The initial timeout is set from {@link ServiceConfig#requestTimeoutMillis()}.
     *
     * <p>For example:
     * <pre>{@code
     * ServiceRequestContext ctx = ...;
     * // Schedules timeout after 1 seconds from now.
     * ctx.setRequestTimeoutAfter(Duration.ofSeconds(1));
     * }</pre>
     *
     * @param requestTimeout the amount of time allowed from now
     */
    void setRequestTimeoutAfter(Duration requestTimeout);

    /**
     * Schedules the request timeout that is triggered at the specified time represented
     * as the number since the epoch ({@code 1970-01-01T00:00:00Z}).
     * Note that the request will be timed out immediately if the specified time is before now.
     * The initial timeout is set from {@link ServiceConfig#requestTimeoutMillis()}.
     *
     * <p>For example:
     * <pre>{@code
     * ServiceRequestContext ctx = ...;
     * // Schedules timeout after 1 seconds from now.
     * long responseTimeoutAt = Instant.now().plus(1, ChronoUnit.SECONDS).toEpochMilli();
     * ctx.setRequestTimeoutAtMillis(responseTimeoutAt);
     * }</pre>
     *
     * @param requestTimeoutAtMillis the request timeout represented as the number of milliseconds
     *                               since the epoch ({@code 1970-01-01T00:00:00Z})
     */
    void setRequestTimeoutAtMillis(long requestTimeoutAtMillis);

    /**
     * Schedules the request timeout that is triggered at the specified time represented
     * as the number since the epoch ({@code 1970-01-01T00:00:00Z}).
     * Note that the request will be timed out immediately if the specified time is before now.
     * The initial timeout is set from {@link ServiceConfig#requestTimeoutMillis()}.
     *
     * <p>For example:
     * <pre>{@code
     * ServiceRequestContext ctx = ...;
     * // Schedules timeout after 1 seconds from now.
     * ctx.setRequestTimeoutAt(Instant.now().plus(1, ChronoUnit.SECONDS));
     * }</pre>
     *
     * @param requestTimeoutAt the request timeout represented as the number of milliseconds
     *                         since the epoch ({@code 1970-01-01T00:00:00Z})
     */
    void setRequestTimeoutAt(Instant requestTimeoutAt);

    /**
     * Returns {@link Request} timeout handler which is executed when
     * receiving the current {@link Request} and sending the corresponding {@link Response}
     * is not completely received within the allowed {@link #requestTimeoutMillis()}.
     */
    @Nullable
    Runnable requestTimeoutHandler();

    /**
     * Sets a handler to run when the request times out. {@code requestTimeoutHandler} must close the response,
     * e.g., by calling {@link HttpResponseWriter#close()}. If not set, the response will be closed with
     * {@link HttpStatus#SERVICE_UNAVAILABLE}.
     *
     * <p>For example,
     * <pre>{@code
     *   HttpResponseWriter res = HttpResponse.streaming();
     *   ctx.setRequestTimeoutHandler(() -> {
     *      res.write(ResponseHeaders.of(HttpStatus.OK,
     *                                   HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8));
     *      res.write(HttpData.ofUtf8("Request timed out."));
     *      res.close();
     *   });
     *   ...
     * }</pre>
     */
    void setRequestTimeoutHandler(Runnable requestTimeoutHandler);

    /**
     * Returns whether this {@link ServiceRequestContext} has been timed-out (e.g., when the
     * corresponding request passes a deadline).
     */
    boolean isTimedOut();

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
     * Returns whether the verbose response mode is enabled. When enabled, the service responses will contain
     * the exception type and its full stack trace, which may be useful for debugging while potentially
     * insecure. When disabled, the service responses will not expose such server-side details to the client.
     */
    boolean verboseResponses();

    /**
     * Returns the {@link AccessLogWriter}.
     */
    AccessLogWriter accessLogWriter();

    /**
     * Returns an immutable {@link HttpHeaders} which is included when a {@link Service} sends an
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
     * Clears the current header and sets the specified {@link HttpHeaders} which is included when a
     * {@link Service} sends an {@link HttpResponse}.
     */
    void setAdditionalResponseHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers);

    /**
     * Adds a header with the specified {@code name} and {@code value}. The header will be included when
     * a {@link Service} sends an {@link HttpResponse}.
     */
    void addAdditionalResponseHeader(CharSequence name, Object value);

    /**
     * Adds the specified {@link HttpHeaders} which is included when a {@link Service} sends an
     * {@link HttpResponse}.
     */
    void addAdditionalResponseHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers);

    /**
     * Removes all headers with the specified {@code name}.
     *
     * @return {@code true} if at least one entry has been removed
     */
    boolean removeAdditionalResponseHeader(CharSequence name);

    /**
     * Returns the {@link HttpHeaders} which is returned along with any other trailers when a
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
     * Clears the current trailer and sets the specified {@link HttpHeaders} which is included when a
     * {@link Service} completes an {@link HttpResponse}.
     */
    void setAdditionalResponseTrailers(Iterable<? extends Entry<? extends CharSequence, ?>> headers);

    /**
     * Adds a trailer with the specified {@code name} and {@code value}. The trailer will be included when
     * a {@link Service} completes an {@link HttpResponse}.
     */
    void addAdditionalResponseTrailer(CharSequence name, Object value);

    /**
     * Adds the specified {@link HttpHeaders} which is included when a {@link Service} completes an
     * {@link HttpResponse}.
     */
    void addAdditionalResponseTrailers(Iterable<? extends Entry<? extends CharSequence, ?>> headers);

    /**
     * Removes all trailers with the specified {@code name}.
     *
     * @return {@code true} if at least one entry has been removed
     */
    boolean removeAdditionalResponseTrailer(CharSequence name);

    /**
     * Returns the proxied addresses of the current {@link Request}.
     */
    ProxiedAddresses proxiedAddresses();
}
