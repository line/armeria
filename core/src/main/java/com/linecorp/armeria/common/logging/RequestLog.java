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

package com.linecorp.armeria.common.logging;

import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.logging.ContentPreviewingClient;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Functions;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.logging.ContentPreviewingService;

/**
 * A set of informational properties collected while processing a {@link Request} and {@link Response}.
 *
 * @see RequestLogAccess#isComplete()
 * @see RequestLogAccess#whenComplete()
 * @see RequestLogAccess#ensureComplete()
 */
public interface RequestLog extends RequestOnlyLog {

    /**
     * Returns a newly created {@link RequestLogBuilder}.
     */
    static RequestLogBuilder builder(RequestContext ctx) {
        requireNonNull(ctx, "ctx");
        return new DefaultRequestLog(ctx);
    }

    /**
     * Returns the time when the processing of the response started, in microseconds since the epoch.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#RESPONSE_START_TIME
     */
    long responseStartTimeMicros();

    /**
     * Returns the time when the processing of the response started, in milliseconds since the epoch.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#RESPONSE_START_TIME
     */
    long responseStartTimeMillis();

    /**
     * Returns the time when the processing of the response started, in nanoseconds. This value can only be
     * used to measure elapsed time and is not related to any other notion of system or wall-clock time.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#RESPONSE_START_TIME
     */
    long responseStartTimeNanos();

    /**
     * Returns the time when the first bytes of the response headers were transferred over the wire. For a
     * client, this is the time the client received the data, while for a server it is the time the server sent
     * them. This value can only be used to measure elapsed time and is not related to any other notion of
     * system or wall-clock time.
     *
     * @return the transfer time, or {@code null} if nothing was transferred.
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#RESPONSE_FIRST_BYTES_TRANSFERRED_TIME
     */
    @Nullable
    Long responseFirstBytesTransferredTimeNanos();

    /**
     * Returns the time when the processing of the response finished, in nanoseconds. This value can only be
     * used to measure elapsed time and is not related to any other notion of system or wall-clock time.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#RESPONSE_END_TIME
     */
    long responseEndTimeNanos();

    /**
     * Returns the duration that was taken to consume or produce the response completely, in nanoseconds.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#RESPONSE_END_TIME
     */
    default long responseDurationNanos() {
        return responseEndTimeNanos() - responseStartTimeNanos();
    }

    /**
     * Returns the amount of time taken since the {@link Request} processing started and until the
     * {@link Response} processing ended. This property is available only when both
     * {@link RequestLogProperty#REQUEST_START_TIME} and {@link RequestLogProperty#RESPONSE_START_TIME} are
     * available.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#RESPONSE_END_TIME
     */
    default long totalDurationNanos() {
        return responseEndTimeNanos() - requestStartTimeNanos();
    }

    /**
     * Returns the length of the response content.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#RESPONSE_LENGTH
     */
    long responseLength();

    /**
     * Returns the cause of response processing failure.
     *
     * @return the cause. {@code null} if the response was processed completely.
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#RESPONSE_CAUSE
     */
    @Nullable
    Throwable responseCause();

    /**
     * Returns the non-informational status {@link ResponseHeaders}.
     * If the {@link Response} was not received or sent at all, it will return a dummy
     * {@link ResponseHeaders} whose {@code :status} is {@code "0"}.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#RESPONSE_HEADERS
     */
    ResponseHeaders responseHeaders();

    /**
     * Returns the high-level content object of the {@link Response}, which is specific
     * to the {@link SerializationFormat}.
     *
     * @return {@link RpcResponse} for RPC, or {@code null} for others
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#RESPONSE_CONTENT
     */
    @Nullable
    Object responseContent();

    /**
     * Returns the low-level content object of the {@link Response}, which is specific
     * to the {@link SerializationFormat}.
     *
     * @return {@code ThriftReply} for Thrift, or {@code null} for others
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#RESPONSE_CONTENT
     */
    @Nullable
    Object rawResponseContent();

    /**
     * Returns the preview of response content of the {@link Response}.
     * Note that a {@link Service} or a {@link Client} must be decorated with {@link ContentPreviewingService}
     * or {@link ContentPreviewingClient} decorators respectively to enable the content preview.
     *
     * @return the preview, or {@code null} if preview is disabled.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#RESPONSE_CONTENT_PREVIEW
     */
    @Nullable
    String responseContentPreview();

    /**
     * Returns the HTTP trailers of the {@link Response}.
     *
     * @throws RequestLogAvailabilityException if the property is not available yet.
     * @see RequestLogProperty#RESPONSE_TRAILERS
     */
    HttpHeaders responseTrailers();

    /**
     * Returns the string representation of the {@link Response}, with no sanitization of headers or content.
     * This method is a shortcut for:
     * <pre>{@code
     * toStringResponseOnly((ctx, headers) -> headers,
     *                      (ctx, content) -> content,
     *                      (ctx, trailers) -> trailers);
     * }</pre>
     */
    default String toStringResponseOnly() {
        return toStringResponseOnly(Functions.second(), Functions.second(), Functions.second());
    }

    /**
     * Returns the string representation of the {@link Response}. This method is a shortcut for:
     * <pre>{@code
     * toStringResponseOnly(headersSanitizer, contentSanitizer, headersSanitizer);
     * }</pre>
     *
     * @param headersSanitizer a {@link BiFunction} for sanitizing HTTP headers for logging. The result of
     *                         the {@link BiFunction} is what is actually logged as headers.
     * @param contentSanitizer a {@link BiFunction} for sanitizing response content for logging. The result of
     *                         the {@link BiFunction} is what is actually logged as content.
     */
    default String toStringResponseOnly(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> headersSanitizer,
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> contentSanitizer) {
        return toStringResponseOnly(headersSanitizer, contentSanitizer, headersSanitizer);
    }

    /**
     * Returns the string representation of the {@link Response}.
     *
     * @param headersSanitizer a {@link BiFunction} for sanitizing HTTP headers for logging. The result of
     *                         the {@link BiFunction} is what is actually logged as headers.
     * @param contentSanitizer a {@link BiFunction} for sanitizing response content for logging. The result of
     *                         the {@link BiFunction} is what is actually logged as content.
     * @param trailersSanitizer a {@link BiFunction} for sanitizing HTTP trailers for logging. The result of
     *                          the {@link BiFunction} is what is actually logged as trailers.
     */
    String toStringResponseOnly(
            BiFunction<? super RequestContext, ? super ResponseHeaders,
                    ? extends @Nullable Object> headersSanitizer,
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable Object> contentSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable Object> trailersSanitizer);
}
