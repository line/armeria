/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.common.logging;

import java.util.function.BiFunction;
import java.util.function.Function;

import javax.net.ssl.SSLSession;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.logging.ContentPreviewingClient;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Functions;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.logging.ContentPreviewingService;

import io.netty.channel.Channel;

/**
 * A set of informational properties for request-side only, collected while consuming a {@link Request}.
 *
 * @see RequestLogAccess#isRequestComplete()
 * @see RequestLogAccess#whenRequestComplete()
 * @see RequestLogAccess#ensureRequestComplete()
 */
public interface RequestOnlyLog extends RequestLogAccess {

    /**
     * Returns the time when the processing of the request started, in microseconds since the epoch.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#REQUEST_START_TIME
     */
    long requestStartTimeMicros();

    /**
     * Returns the time when the processing of the request started, in milliseconds since the epoch.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#REQUEST_START_TIME
     */
    long requestStartTimeMillis();

    /**
     * Returns the time when the processing of the request started, in nanoseconds. This value can only be
     * used to measure elapsed time and is not related to any other notion of system or wall-clock time.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#REQUEST_START_TIME
     */
    long requestStartTimeNanos();

    /**
     * Returns the time when the first bytes of the request headers were transferred over the wire. For a
     * client, this is the time the client sent the data, while for a server it is the time the server received
     * them. This value can only be used to measure elapsed time and is not related to any other notion of
     * system or wall-clock time.
     *
     * @return the transfer time, or {@code null} if nothing was transferred.
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#REQUEST_FIRST_BYTES_TRANSFERRED_TIME
     */
    @Nullable
    Long requestFirstBytesTransferredTimeNanos();

    /**
     * Returns the time when the processing of the request finished, in nanoseconds. This value can only be
     * used to measure elapsed time and is not related to any other notion of system or wall-clock time.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#REQUEST_END_TIME
     */
    long requestEndTimeNanos();

    /**
     * Returns the duration that was taken to consume or produce the request completely, in nanoseconds.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#REQUEST_END_TIME
     */
    default long requestDurationNanos() {
        return requestEndTimeNanos() - requestStartTimeNanos();
    }

    /**
     * Returns the length of the request content.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#REQUEST_LENGTH
     */
    long requestLength();

    /**
     * Returns the cause of request processing failure.
     *
     * @return the cause, or {@code null} if the request was processed completely.
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#REQUEST_CAUSE
     */
    @Nullable
    Throwable requestCause();

    /**
     * Returns the Netty {@link Channel} which handled the {@link Request}.
     *
     * @return the Netty {@link Channel}, or {@code null} if the {@link Request} has failed even before
     *         a connection is established.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#SESSION
     */
    @Nullable
    Channel channel();

    /**
     * Returns the {@link SSLSession} of the connection which handled the {@link Request}.
     *
     * @return the {@link SSLSession}, or {@code null} if the {@link Request} has failed even before
     *         a TLS connection is established or the connection does not use TLS.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#SESSION
     */
    @Nullable
    SSLSession sslSession();

    /**
     * Returns the {@link SessionProtocol} of the {@link Request}.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#SESSION
     */
    SessionProtocol sessionProtocol();

    /**
     * Returns the {@link ClientConnectionTimings} of the {@link Request}.
     *
     * @return the {@link ClientConnectionTimings} if the {@link Request} involved a new connection attempt,
     *         or {@code null} otherwise.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#SESSION
     */
    @Nullable
    ClientConnectionTimings connectionTimings();

    /**
     * Returns the {@link SerializationFormat} of the {@link Request}.
     */
    SerializationFormat serializationFormat();

    /**
     * Returns the {@link Scheme} of the {@link Request}.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#SCHEME
     */
    Scheme scheme();

    /**
     * Returns the human-readable name of the service that served the {@link Request}, such as:
     * <ul>
     *   <li>gRPC - a service name (e.g, {@code com.foo.GrpcService})</li>
     *   <li>Thrift - a service type (e.g, {@code com.foo.ThriftService$AsyncIface} or
     *       {@code com.foo.ThriftService$Iface})</li>
     *   <li>{@link HttpService} and annotated service - an innermost class name</li>
     * </ul>
     * This property is often used as a meter tag or distributed trace's span name.
     */
    @Nullable
    String serviceName();

    /**
     * Returns the human-readable simple name of the {@link Request}, such as:
     * <ul>
     *   <li>gRPC - A capitalized method name defined in {@code io.grpc.MethodDescriptor}
     *       (e.g, {@code GetItems})</li>
     *   <li>Thrift and annotated service - a method name (e.g, {@code getItems})</li>
     *   <li>{@link HttpService} - an HTTP method name</li>
     * </ul>
     * This property is often used as a meter tag or distributed trace's span name.
     */
    String name();

    /**
     * Returns the human-readable full name, which is the concatenation of {@link #serviceName()} and
     * {@link #name()} using {@code '/'}, of the {@link Request}.
     * This property is often used as a meter tag or distributed trace's span name.
     */
    String fullName();

    /**
     * Returns the authenticated user which is used to print {@code %u} format of an access log.
     *
     * @see <a href="https://httpd.apache.org/docs/current/mod/mod_log_config.html">Custom Log Formats</a>
     */
    @Nullable
    String authenticatedUser();

    /**
     * Returns the {@link RequestHeaders}. If the {@link Request} was not received or sent at all,
     * it will return a dummy {@link RequestHeaders} whose {@code :authority} and {@code :path} are
     * set to {@code "?"}, {@code :scheme} is set to {@code "http"} or {@code "https"}, and {@code :method} is
     * set to {@code "UNKNOWN"}.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#REQUEST_HEADERS
     */
    RequestHeaders requestHeaders();

    /**
     * Returns the high-level content object of the {@link Request}, which is specific
     * to the {@link SerializationFormat}.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#REQUEST_CONTENT
     */
    @Nullable
    Object requestContent();

    /**
     * Returns the low-level content object of the {@link Request}, which is specific
     * to the {@link SerializationFormat}.
     *
     * @return {@code ThriftCall} for Thrift, or {@code null} for others
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#REQUEST_CONTENT
     */
    @Nullable
    Object rawRequestContent();

    /**
     * Returns the preview of request content of the {@link Request}.
     * Note that a {@link Service} or a {@link Client} must be decorated with {@link ContentPreviewingService}
     * or {@link ContentPreviewingClient} decorators respectively to enable the content preview.
     *
     * @return the preview, or {@code null} if the preview is disabled.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#REQUEST_CONTENT_PREVIEW
     */
    @Nullable
    String requestContentPreview();

    /**
     * Returns the HTTP trailers of the {@link Request}.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#REQUEST_TRAILERS
     */
    HttpHeaders requestTrailers();

    /**
     * Returns the current attempt number of the {@link Request}.
     * It returns {@code 0} for the very first request. It returns {@code 1} for the first retry.
     * It returns {@code 2} for the second retry, and so forth.
     */
    int currentAttempt();

    /**
     * Returns the string representation of the {@link Request}, with no sanitization of headers or content.
     * This method is a shortcut for:
     * <pre>{@code
     * toStringRequestOnly((ctx, headers) -> headers,
     *                     (ctx, content) -> content,
     *                     (ctx, trailers) -> trailers);
     * }</pre>
     * @deprecated Use {@link LogFormatter#formatRequest(RequestOnlyLog)} instead.
     */
    @Deprecated
    default String toStringRequestOnly() {
        return toStringRequestOnly(Functions.second(), Functions.second(), Functions.second());
    }

    /**
     * Returns the string representation of the {@link Request}. This method is a shortcut for:
     * <pre>{@code
     * toStringRequestOnly(headersSanitizer, contentSanitizer, headersSanitizer);
     * }</pre>
     *
     * @param headersSanitizer a {@link BiFunction} for sanitizing HTTP headers for logging. The result of
     *                         the {@link BiFunction} is what is actually logged as headers.
     * @param contentSanitizer a {@link BiFunction} for sanitizing request content for logging. The result of
     *                         the {@link BiFunction} is what is actually logged as content.
     * @deprecated Use {@link LogFormatter#formatRequest(RequestOnlyLog)} instead.
     */
    @Deprecated
    default String toStringRequestOnly(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> headersSanitizer,
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> contentSanitizer) {
        return toStringRequestOnly(headersSanitizer, contentSanitizer, headersSanitizer);
    }

    /**
     * Returns the string representation of the {@link Request}.
     *
     * @param headersSanitizer a {@link BiFunction} for sanitizing HTTP headers for logging. The result of
     *                         the {@link BiFunction} is what is actually logged as headers.
     * @param contentSanitizer a {@link Function} for sanitizing request content for logging. The result of
     *                         the {@link BiFunction} is what is actually logged as content.
     * @param trailersSanitizer a {@link BiFunction} for sanitizing HTTP trailers for logging. The result of
     *                          the {@link BiFunction} is what is actually logged as trailers.
     * @deprecated Use {@link LogFormatter#formatRequest(RequestOnlyLog)} instead.
     */
    @Deprecated
    default String toStringRequestOnly(
            BiFunction<? super RequestContext, ? super RequestHeaders,
                    ? extends @Nullable Object> headersSanitizer,
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> contentSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> trailersSanitizer) {
        return LogFormatter.builderForText()
                           .requestHeadersSanitizer((ctx, headers) -> {
                               final RequestHeaders requestHeaders = (RequestHeaders) headers;
                               final Object sanitized = headersSanitizer.apply(ctx, requestHeaders);
                               if (sanitized == null) {
                                   return "<sanitized>";
                               }
                               return sanitized.toString();
                           })
                           .requestTrailersSanitizer((ctx, headers) -> {
                               final Object sanitized = trailersSanitizer.apply(ctx, headers);
                               if (sanitized == null) {
                                   return "<sanitized>";
                               }
                               return sanitized.toString();
                           })
                           .requestContentSanitizer((ctx, content) -> {
                               final Object sanitized = contentSanitizer.apply(ctx, content);
                               if (sanitized == null) {
                                   return "<sanitized>";
                               }
                               return sanitized.toString();
                           })
                           .includeContext(false)
                           .build()
                           .formatRequest(this);
    }
}
