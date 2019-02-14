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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Response;

import io.netty.handler.codec.Headers;
import io.netty.util.AsciiString;

/**
 * Provides information about an invocation and related utilities. Every request being handled has its own
 * {@link ServiceRequestContext} instance.
 */
public interface ServiceRequestContext extends RequestContext {

    /**
     * Returns a new {@link ServiceRequestContext} created from the specified {@link HttpRequest}.
     * Note that it is not usually required to create a new context by yourself, because Armeria
     * will always provide a context object for you. However, it may be useful in some cases such as
     * unit testing.
     *
     * @see ServiceRequestContextBuilder
     */
    static ServiceRequestContext of(HttpRequest request) {
        return ServiceRequestContextBuilder.of(request).build();
    }

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

    @Override
    ServiceRequestContext newDerivedContext();

    @Override
    ServiceRequestContext newDerivedContext(Request request);

    /**
     * Returns the {@link Server} that is handling the current {@link Request}.
     */
    Server server();

    /**
     * Returns the {@link VirtualHost} that is handling the current {@link Request}.
     */
    VirtualHost virtualHost();

    /**
     * Returns the {@link PathMapping} associated with the {@link Service} that is handling the current
     * {@link Request}.
     */
    PathMapping pathMapping();

    /**
     * Returns the {@link PathMappingContext} used to find the {@link Service}.
     */
    PathMappingContext pathMappingContext();

    /**
     * Returns the path parameters mapped by the {@link PathMapping} associated with the {@link Service}
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
     * Returns the {@link Service} that is handling the current {@link Request}.
     */
    <T extends Service<HttpRequest, HttpResponse>> T service();

    /**
     * Returns the {@link ExecutorService} that could be used for executing a potentially long-running task.
     * The {@link ExecutorService} will propagate the {@link ServiceRequestContext} automatically when running
     * a task.
     *
     * <p>Note that performing a long-running task in {@link Service#serve(ServiceRequestContext, Request)}
     * may block the {@link Server}'s I/O event loop and thus should be executed in other threads.
     */
    ExecutorService blockingTaskExecutor();

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
     * Returns the {@link Logger} of the {@link Service}.
     *
     * @deprecated Use a logging framework integration such as {@code RequestContextExportingAppender} in
     *             {@code armeria-logback}.
     */
    @Deprecated
    Logger logger();

    /**
     * Returns the amount of time allowed until receiving the current {@link Request} and sending
     * the corresponding {@link Response} completely.
     * This value is initially set from {@link ServerConfig#defaultRequestTimeoutMillis()}.
     */
    long requestTimeoutMillis();

    /**
     * Sets the amount of time allowed until receiving the current {@link Request} and sending
     * the corresponding {@link Response} completely.
     * This value is initially set from {@link ServerConfig#defaultRequestTimeoutMillis()}.
     */
    void setRequestTimeoutMillis(long requestTimeoutMillis);

    /**
     * Sets the amount of time allowed until receiving the current {@link Request} and sending
     * the corresponding {@link Response} completely.
     * This value is initially set from {@link ServerConfig#defaultRequestTimeoutMillis()}.
     */
    void setRequestTimeout(Duration requestTimeout);

    /**
     * Sets a handler to run when the request times out. {@code requestTimeoutHandler} must close the response,
     * e.g., by calling {@link HttpResponseWriter#close()}. If not set, the response will be closed with
     * {@link HttpStatus#SERVICE_UNAVAILABLE}.
     *
     * <p>For example,
     * <pre>{@code
     *   HttpResponseWriter res = HttpResponse.streaming();
     *   ctx.setRequestTimeoutHandler(() -> {
     *      res.write(HttpHeaders.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Request timed out."));
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
    @Override
    boolean isTimedOut();

    /**
     * Returns the maximum length of the current {@link Request}.
     * This value is initially set from {@link ServerConfig#defaultMaxRequestLength()}.
     * If 0, there is no limit on the request size.
     *
     * @see ContentTooLargeException
     */
    long maxRequestLength();

    /**
     * Sets the maximum length of the current {@link Request}.
     * This value is initially set from {@link ServerConfig#defaultMaxRequestLength()}.
     * If 0, there is no limit on the request size.
     *
     * @see ContentTooLargeException
     */
    void setMaxRequestLength(long maxRequestLength);

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
    void setAdditionalResponseHeader(AsciiString name, String value);

    /**
     * Clears the current header and sets the specified {@link Headers} which is included when a
     * {@link Service} sends an {@link HttpResponse}.
     */
    void setAdditionalResponseHeaders(Headers<? extends AsciiString, ? extends String, ?> headers);

    /**
     * Adds a header with the specified {@code name} and {@code value}. The header will be included when
     * a {@link Service} sends an {@link HttpResponse}.
     */
    void addAdditionalResponseHeader(AsciiString name, String value);

    /**
     * Adds the specified {@link Headers} which is included when a {@link Service} sends an
     * {@link HttpResponse}.
     */
    void addAdditionalResponseHeaders(Headers<? extends AsciiString, ? extends String, ?> headers);

    /**
     * Removes all headers with the specified {@code name}.
     *
     * @return {@code true} if at least one entry has been removed
     */
    boolean removeAdditionalResponseHeader(AsciiString name);

    /**
     * Returns an immutable {@link HttpHeaders} which is returned along with any other trailers when a
     * {@link Service} completes an {@link HttpResponse}.
     */
    HttpHeaders additionalResponseTrailers();

    /**
     * Sets a trailer with the specified {@code name} and {@code value}. This will remove all previous values
     * associated with the specified {@code name}.
     * The trailer will be included when a {@link Service} completes an {@link HttpResponse}.
     */
    void setAdditionalResponseTrailer(AsciiString name, String value);

    /**
     * Clears the current trailer and sets the specified {@link Headers} which is included when a
     * {@link Service} completes an {@link HttpResponse}.
     */
    void setAdditionalResponseTrailers(Headers<? extends AsciiString, ? extends String, ?> headers);

    /**
     * Adds a trailer with the specified {@code name} and {@code value}. The trailer will be included when
     * a {@link Service} completes an {@link HttpResponse}.
     */
    void addAdditionalResponseTrailer(AsciiString name, String value);

    /**
     * Adds the specified {@link Headers} which is included when a {@link Service} completes an
     * {@link HttpResponse}.
     */
    void addAdditionalResponseTrailers(Headers<? extends AsciiString, ? extends String, ?> headers);

    /**
     * Removes all trailers with the specified {@code name}.
     *
     * @return {@code true} if at least one entry has been removed
     */
    boolean removeAdditionalResponseTrailer(AsciiString name);

    /**
     * Returns the proxied addresses if the current {@link Request} is received through a proxy.
     */
    @Nullable
    ProxiedAddresses proxiedAddresses();
}
