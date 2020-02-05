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
import java.time.Instant;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.RequestContextThreadLocal;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

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
     * @throws IllegalStateException if the current context is not a {@link ClientRequestContext}.
     */
    @Nullable
    static ClientRequestContext currentOrNull() {
        final RequestContext ctx = RequestContext.currentOrNull();
        if (ctx == null) {
            return null;
        }
        checkState(ctx instanceof ClientRequestContext,
                   "The current context is not a client-side context: %s", ctx);
        return (ClientRequestContext) ctx;
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
     * Returns the {@link ServiceRequestContext} whose {@link Service} invokes the {@link Client}
     * {@link Request} which created this {@link ClientRequestContext}, or {@code null} if this client request
     * was not made in the context of a server request.
     */
    @Nullable
    ServiceRequestContext root();

    /**
     * Returns the value mapped to the given {@link AttributeKey} or {@code null} if there's no value set by
     * {@link #setAttr(AttributeKey, Object)} or {@link #setAttrIfAbsent(AttributeKey, Object)}.
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
    @Override
    <V> V attr(AttributeKey<V> key);

    /**
     * Returns the value mapped to the given {@link AttributeKey} or {@code null} if there's no value set by
     * {@link #setAttr(AttributeKey, Object)} or {@link #setAttrIfAbsent(AttributeKey, Object)}.
     * Unlike {@link #attr(AttributeKey)}, this does not search in {@link #root()}.
     *
     * @see #attr(AttributeKey)
     */
    @Nullable
    <V> V ownAttr(AttributeKey<V> key);

    /**
     * Returns the {@link Iterator} of all {@link Entry}s this context contains.
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
    @Override
    Iterator<Entry<AttributeKey<?>, Object>> attrs();

    /**
     * Returns the {@link Iterator} of all {@link Entry}s this context contains.
     * Unlike {@link #attrs()}, this does not iterate {@link #root()}.
     *
     * @see #attrs()
     */
    Iterator<Entry<AttributeKey<?>, Object>> ownAttrs();

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
    default SafeCloseable push() {
        final RequestContext oldCtx = RequestContextThreadLocal.getAndSet(this);
        if (oldCtx == this) {
            // Reentrance
            return noopSafeCloseable();
        }

        if (oldCtx == null) {
            return RequestContextThreadLocal::remove;
        }

        final ServiceRequestContext root = root();
        if ((oldCtx instanceof ServiceRequestContext && oldCtx == root) ||
            oldCtx instanceof ClientRequestContext && ((ClientRequestContext) oldCtx).root() == root) {
            return () -> RequestContextThreadLocal.set(oldCtx);
        }

        // Put the oldCtx back before throwing an exception.
        RequestContextThreadLocal.set(oldCtx);
        throw newIllegalContextPushingException(this, oldCtx);
    }

    /**
     * Creates a new {@link ClientRequestContext} whose properties and {@link Attribute}s are copied from this
     * {@link ClientRequestContext}, except having a different {@link Request} and its own {@link RequestLog}.
     */
    @Override
    default ClientRequestContext newDerivedContext(RequestId id,
                                                   @Nullable HttpRequest req,
                                                   @Nullable RpcRequest rpcReq) {
        final Endpoint endpoint = endpoint();
        checkState(endpoint != null, "endpoint not available");
        return newDerivedContext(id, req, rpcReq, endpoint);
    }

    /**
     * Creates a new {@link ClientRequestContext} whose properties and {@link Attribute}s are copied from this
     * {@link ClientRequestContext}, except having different {@link Request}, {@link Endpoint} and its own
     * {@link RequestLog}.
     */
    ClientRequestContext newDerivedContext(RequestId id, @Nullable HttpRequest req, @Nullable RpcRequest rpcReq,
                                           Endpoint endpoint);

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
     * <a href="https://tools.ietf.org/html/rfc3986#section-3.5">the section 3.5 of RFC3986</a>.
     *
     * @return the fragment part of the request URI, or {@code null} if no fragment was specified
     */
    @Nullable
    String fragment();

    /**
     * Returns the amount of time allowed until the initial write attempt of the current {@link Request}
     * succeeds. This value is initially set from {@link ClientOption#WRITE_TIMEOUT_MILLIS}.
     */
    long writeTimeoutMillis();

    /**
     * Returns the amount of time allowed until the initial write attempt of the current {@link Request}
     * succeeds. This value is initially set from {@link ClientOption#WRITE_TIMEOUT_MILLIS}.
     */
    void setWriteTimeoutMillis(long writeTimeoutMillis);

    /**
     * Returns the amount of time allowed until the initial write attempt of the current {@link Request}
     * succeeds. This value is initially set from {@link ClientOption#WRITE_TIMEOUT_MILLIS}.
     */
    void setWriteTimeout(Duration writeTimeout);

    /**
     * Returns the amount of time allowed until receiving the {@link Response} completely
     * since the transfer of the {@link Response} started. This value is initially set from
     * {@link ClientOption#RESPONSE_TIMEOUT_MILLIS}.
     */
    long responseTimeoutMillis();

    /**
     * Clears the previously scheduled response timeout, if any.
     * Note that calling this will prevent the response from ever being timed out.
     */
    void clearResponseTimeout();

    /**
     * Schedules the response timeout that is triggered when the {@link Response} is not
     * fully received within the specified amount of time since the {@link Response} started
     * or {@link Request} was fully sent.
     * This value is initially set from {@link ClientOption#RESPONSE_TIMEOUT_MILLIS}.
     *
     * <p>For example:
     * <pre>{@code
     * ClientRequestContext ctx = ...;
     * ctx.setResponseTimeoutMillis(1000);
     * assert ctx.responseTimeoutMillis() == 1000;
     * ctx.setResponseTimeoutMillis(2000);
     * assert ctx.responseTimeoutMillis() == 2000;
     * }</pre>
     *
     * @param responseTimeoutMillis the amount of time allowed in milliseconds from
     *                              the beginning of the response
     *
     * @deprecated Use {@link #extendResponseTimeoutMillis(long)}, {@link #setResponseTimeoutAfterMillis(long)},
     *             {@link #setResponseTimeoutAfterMillis(long)} or {@link #clearResponseTimeout()}
     */
    @Deprecated
    void setResponseTimeoutMillis(long responseTimeoutMillis);

    /**
     * Schedules the response timeout that is triggered when the {@link Response} is not
     * fully received within the specified amount of time since the {@link Response} started
     * or {@link Request} was fully sent.
     * This value is initially set from {@link ClientOption#RESPONSE_TIMEOUT_MILLIS}.
     *
     * <p>For example:
     * <pre>{@code
     * ClientRequestContext ctx = ...;
     * ctx.setResponseTimeout(Duration.ofSeconds(1));
     * assert ctx.responseTimeoutMillis() == 1000;
     * ctx.setResponseTimeout(Duration.ofSeconds(2));
     * assert ctx.responseTimeoutMillis() == 2000;
     * }</pre>
     *
     * @param responseTimeout the amount of time allowed from the beginning of the response
     *
     * @deprecated Use {@link #extendResponseTimeout(Duration)}, {@link #setResponseTimeoutAfter(Duration)},
     *             {@link #setResponseTimeoutAt(Instant)} or {@link #clearResponseTimeout()}
     */
    @Deprecated
    void setResponseTimeout(Duration responseTimeout);

    /**
     * Extends the previously scheduled response timeout by
     * the specified amount of {@code adjustmentMillis}.
     * This method does nothing if no response timeout was scheduled previously.
     * Note that a negative {@code adjustmentMillis} reduces the current timeout.
     * The initial timeout is set from {@link ClientOption#RESPONSE_TIMEOUT_MILLIS}.
     *
     * <p>For example:
     * <pre>{@code
     * ClientRequestContext ctx = ...;
     * long oldResponseTimeoutMillis = ctx.responseTimeoutMillis();
     * ctx.extendResponseTimeoutMillis(1000);
     * assert ctx.responseTimeoutMillis() == oldResponseTimeoutMillis + 1000;
     * ctx.extendResponseTimeoutMillis(-500);
     * assert ctx.responseTimeoutMillis() == oldResponseTimeoutMillis + 500;
     * }</pre>
     *
     * @param adjustmentMillis the amount of time in milliseconds to extend the current timeout by
     */
    void extendResponseTimeoutMillis(long adjustmentMillis);

    /**
     * Extends the previously scheduled response timeout by the specified amount of {@code adjustment}.
     * This method does nothing if no response timeout was scheduled previously.
     * Note that a negative {@code adjustment} reduces the current timeout.
     * The initial timeout is set from {@link ClientOption#RESPONSE_TIMEOUT_MILLIS}.
     *
     * <p>For example:
     * <pre>{@code
     * ClientRequestContext ctx = ...;
     * long oldResponseTimeoutMillis = ctx.responseTimeoutMillis();
     * ctx.extendResponseTimeout(Duration.ofSeconds(1));
     * assert ctx.responseTimeoutMillis() == oldResponseTimeoutMillis + 1000;
     * ctx.extendResponseTimeout(Duration.ofMillis(-500));
     * assert ctx.responseTimeoutMillis() == oldResponseTimeoutMillis + 500;
     * }</pre>
     *
     * @param adjustment the amount of time to extend the current timeout by
     */
    void extendResponseTimeout(Duration adjustment);

    /**
     * Schedules the response timeout that is triggered when the {@link Response} is not
     * fully received within the specified amount of time from now.
     * Note that the specified {@code responseTimeoutMillis} must be positive.
     * The initial timeout is set from {@link ClientOption#RESPONSE_TIMEOUT_MILLIS}.
     *
     * <p>For example:
     * <pre>{@code
     * ClientRequestContext ctx = ...;
     * // Schedules timeout after 1 seconds from now.
     * ctx.setResponseTimeoutAfterMillis(1000);
     * }</pre>
     *
     * @param responseTimeoutMillis the amount of time allowed in milliseconds from now
     */
    void setResponseTimeoutAfterMillis(long responseTimeoutMillis);

    /**
     * Schedules the response timeout that is triggered when the {@link Response} is not
     * fully received within the specified amount of time from now.
     * Note that the specified {@code responseTimeout} must be positive.
     * The initial timeout is set from {@link ClientOption#RESPONSE_TIMEOUT_MILLIS}.
     *
     * <p>For example:
     * <pre>{@code
     * ClientRequestContext ctx = ...;
     * // Schedules timeout after 1 seconds from now.
     * ctx.setResponseTimeoutAfter(Duration.ofSeconds(1));
     * }</pre>
     *
     * @param responseTimeout the amount of time allowed from now
     */
    void setResponseTimeoutAfter(Duration responseTimeout);

    /**
     * Schedules the response timeout that is triggered at the specified time represented
     * as the number since the epoch ({@code 1970-01-01T00:00:00Z}).
     * Note that the response will be timed out immediately if the specified time is before now.
     * The initial timeout is set from {@link ClientOption#RESPONSE_TIMEOUT_MILLIS}.
     *
     * <p>For example:
     * <pre>{@code
     * ClientRequestContext ctx = ...;
     * // Schedules timeout after 1 seconds from now.
     * long responseTimeoutAt = Instant.now().plus(1, ChronoUnit.SECONDS).toEpochMilli();
     * ctx.setResponseTimeoutAtMillis(responseTimeoutAt);
     * }</pre>
     *
     * @param responseTimeoutAtMillis the response timeout represented as the number of milliseconds
     *                                since the epoch ({@code 1970-01-01T00:00:00Z})
     */
    void setResponseTimeoutAtMillis(long responseTimeoutAtMillis);

    /**
     * Schedules the response timeout that is triggered at the specified time represented
     * as the number of milliseconds since the epoch ({@code 1970-01-01T00:00:00Z}).
     * Note that the response will be timed out immediately if the specified time is before now.
     * The initial timeout is set from {@link ClientOption#RESPONSE_TIMEOUT_MILLIS}.
     *
     * <p>For example:
     * <pre>{@code
     * ClientRequestContext ctx = ...;
     * // Schedules timeout after 1 seconds from now.
     * ctx.setResponseTimeoutAt(Instant.now().plus(1, ChronoUnit.SECONDS));
     * }</pre>
     *
     * @param responseTimeoutAt the response timeout represented as the number of milliseconds
     *                          since the epoch ({@code 1970-01-01T00:00:00Z})
     */
    void setResponseTimeoutAt(Instant responseTimeoutAt);

    /**
     * Returns {@link Response} timeout handler which is executed when
     * the {@link Response} is not completely received within the allowed {@link #responseTimeoutMillis()}
     * or the default {@link ClientOption#RESPONSE_TIMEOUT_MILLIS}.
     */
    @Nullable
    Runnable responseTimeoutHandler();

    /**
     * Sets a handler to run when the response times out. {@code responseTimeoutHandler} must abort
     * the response, e.g., by calling {@link HttpResponseWriter#abort(Throwable)}.
     * If not set, the response will be closed with {@link ResponseTimeoutException}.
     *
     * <p>For example,
     * <pre>{@code
     * HttpResponseWriter res = HttpResponse.streaming();
     * ctx.setResponseTimeoutHandler(() -> {
     *    res.abort(new IllegalStateException("Server is in a bad state."));
     * });
     * ...
     * }</pre>
     */
    void setResponseTimeoutHandler(Runnable responseTimeoutHandler);

    /**
     * Returns the maximum length of the received {@link Response}.
     * This value is initially set from {@link ClientOption#MAX_RESPONSE_LENGTH}.
     *
     * @return the maximum length of the response. {@code 0} if unlimited.
     *
     * @see ContentTooLargeException
     */
    long maxResponseLength();

    /**
     * Sets the maximum length of the received {@link Response}.
     * This value is initially set from {@link ClientOption#MAX_RESPONSE_LENGTH}.
     * Specify {@code 0} to disable the limit of the length of a response.
     *
     * @see ContentTooLargeException
     */
    void setMaxResponseLength(long maxResponseLength);

    /**
     * Returns an {@link HttpHeaders} which is included when a {@link Client} sends an {@link HttpRequest}.
     */
    HttpHeaders additionalRequestHeaders();

    /**
     * Sets a header with the specified {@code name} and {@code value}. This will remove all previous values
     * associated with the specified {@code name}.
     * The header will be included when a {@link Client} sends an {@link HttpRequest}.
     */
    void setAdditionalRequestHeader(CharSequence name, Object value);

    /**
     * Clears the current header and sets the specified {@link HttpHeaders} which is included when a
     * {@link Client} sends an {@link HttpRequest}.
     */
    void setAdditionalRequestHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers);

    /**
     * Adds a header with the specified {@code name} and {@code value}. The header will be included when
     * a {@link Client} sends an {@link HttpRequest}.
     */
    void addAdditionalRequestHeader(CharSequence name, Object value);

    /**
     * Adds the specified {@link HttpHeaders} which is included when a {@link Client} sends an
     * {@link HttpRequest}.
     */
    void addAdditionalRequestHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers);

    /**
     * Removes all headers with the specified {@code name}.
     *
     * @return {@code true} if at least one entry has been removed
     */
    boolean removeAdditionalRequestHeader(CharSequence name);
}
