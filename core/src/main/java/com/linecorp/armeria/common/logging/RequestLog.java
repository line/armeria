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

import java.util.Arrays;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.channel.Channel;

/**
 * A set of informational properties collected while processing a {@link Request} and its {@link Response}.
 * The properties provided by this class are not always fully available. Check the availability of each
 * property using {@link #isAvailable(RequestLogAvailability)} or {@link #availabilities()}. Attempting to
 * access the properties that are not available yet will cause a {@link RequestLogAvailabilityException}.
 * Use {@link #addListener(RequestLogListener, RequestLogAvailability)} to get notified when the interested
 * properties are available.
 *
 * @see RequestContext#log()
 * @see RequestLogAvailability
 * @see RequestLogListener
 */
public interface RequestLog {

    /**
     * Returns the set of satisfied {@link RequestLogAvailability}s.
     */
    Set<RequestLogAvailability> availabilities();

    /**
     * Returns {@code true} if the specified {@link RequestLogAvailability} is satisfied.
     */
    boolean isAvailable(RequestLogAvailability availability);

    /**
     * Returns {@code true} if all of the specified {@link RequestLogAvailability}s are satisfied.
     */
    default boolean isAvailable(RequestLogAvailability... availabilities) {
        for (RequestLogAvailability k : requireNonNull(availabilities, "availabilities")) {
            if (!isAvailable(k)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} if all of the specified {@link RequestLogAvailability}s are satisfied.
     */
    default boolean isAvailable(Iterable<RequestLogAvailability> availabilities) {
        for (RequestLogAvailability k : requireNonNull(availabilities, "availabilities")) {
            if (!isAvailable(k)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Ensures that the specified {@link RequestLogAvailability} is satisfied.
     *
     * @throws RequestLogAvailabilityException if not satisfied yet
     */
    default void ensureAvailability(RequestLogAvailability availability) {
        if (!isAvailable(availability)) {
            throw new RequestLogAvailabilityException(availability.name());
        }
    }

    /**
     * Ensures that all of the specified {@link RequestLogAvailability}s are satisfied.
     *
     * @throws RequestLogAvailabilityException if not satisfied yet
     */
    default void ensureAvailability(RequestLogAvailability... availabilities) {
        if (!isAvailable(availabilities)) {
            throw new RequestLogAvailabilityException(Arrays.toString(availabilities));
        }
    }

    /**
     * Ensures that all of the specified {@link RequestLogAvailability}s are satisfied.
     *
     * @throws RequestLogAvailabilityException if not satisfied yet
     */
    default void ensureAvailability(Iterable<RequestLogAvailability> properties) {
        if (!isAvailable(properties)) {
            throw new RequestLogAvailabilityException(properties.toString());
        }
    }

    /**
     * Adds the specified {@link RequestLogListener} so that it's notified when the specified
     * {@link RequestLogAvailability} is satisfied.
     */
    void addListener(RequestLogListener listener, RequestLogAvailability availability);

    /**
     * Adds the specified {@link RequestLogListener} so that it's notified when all of the specified
     * {@link RequestLogAvailability}s are satisfied.
     */
    void addListener(RequestLogListener listener, RequestLogAvailability... availabilities);

    /**
     * Adds the specified {@link RequestLogListener} so that it's notified when all of the specified
     * {@link RequestLogAvailability}s are satisfied.
     */
    void addListener(RequestLogListener listener, Iterable<RequestLogAvailability> availabilities);

    /**
     * Returns the {@link RequestContext} associated with the {@link Request} being handled.
     * This method returns non-{@code null} regardless the current {@link RequestLogAvailability}.
     */
    RequestContext context();

    /**
     * Returns the method of the {@link Request}. This method is a shortcut to {@code context().method()}.
     * This method returns non-{@code null} regardless the current {@link RequestLogAvailability}.
     */
    default HttpMethod method() {
        return context().method();
    }

    /**
     * Returns the absolute path part of the {@link Request} URI, excluding the query part,
     * as defined in <a href="https://tools.ietf.org/html/rfc3986">RFC3986</a>.
     * This method is a shortcut to {@code context().path()}.
     * This method returns non-{@code null} regardless the current {@link RequestLogAvailability}.
     */
    default String path() {
        return context().path();
    }

    /**
     * Returns the query part of the {@link Request} URI, without the leading {@code '?'},
     * as defined in <a href="https://tools.ietf.org/html/rfc3986">RFC3986</a>.
     * This method is a shortcut to {@code context().query()}.
     * This method returns non-{@code null} regardless the current {@link RequestLogAvailability}.
     */
    @Nullable
    default String query() {
        return context().query();
    }

    /**
     * Returns the time when the processing of the request started, in millis since the epoch.
     *
     * @throws RequestLogAvailabilityException if this property is not available yet
     */
    long requestStartTimeMillis();

    /**
     * Returns the duration that was taken to consume or produce the request completely, in nanoseconds.
     *
     * @throws RequestLogAvailabilityException if this property is not available yet
     */
    long requestDurationNanos();

    /**
     * Returns the length of the request content.
     *
     * @throws RequestLogAvailabilityException if this property is not available yet
     */
    long requestLength();

    /**
     * Returns the cause of request processing failure.
     *
     * @return the cause. {@code null} if the request was processed completely.
     * @throws RequestLogAvailabilityException if this property is not available yet
     */
    @Nullable
    Throwable requestCause();

    /**
     * Returns the time when the processing of the response started, in millis since the epoch.
     *
     * @throws RequestLogAvailabilityException if this property is not available yet
     */
    long responseStartTimeMillis();

    /**
     * Returns the duration that was taken to consume or produce the response completely, in nanoseconds.
     *
     * @throws RequestLogAvailabilityException if this property is not available yet
     */
    long responseDurationNanos();

    /**
     * Returns the length of the response content.
     *
     * @throws RequestLogAvailabilityException if this property is not available yet
     */
    long responseLength();

    /**
     * Returns the cause of response processing failure.
     *
     * @return the cause. {@code null} if the response was processed completely.
     * @throws RequestLogAvailabilityException if this property is not available yet
     */
    @Nullable
    Throwable responseCause();

    /**
     * Returns the amount of time taken since the {@link Request} processing started and until the
     * {@link Response} processing ended. This property is available only when both
     * {@link RequestLogAvailability#REQUEST_START} and {@link RequestLogAvailability#RESPONSE_END} are
     * available.
     *
     * @throws RequestLogAvailabilityException if this property is not available yet
     */
    long totalDurationNanos();

    /**
     * Returns the Netty {@link Channel} which handled the {@link Request}.
     *
     * @return the Netty {@link Channel}. {@code null} if the {@link Request} has failed even before
     *         a {@link Request} is assigned to a {@link Channel}.
     *
     * @throws RequestLogAvailabilityException if this property is not available yet
     */
    @Nullable
    Channel channel();

    /**
     * Returns the {@link SessionProtocol} of the {@link Request}.
     *
     * @throws RequestLogAvailabilityException if this property is not available yet
     */
    SessionProtocol sessionProtocol();

    /**
     * Returns the {@link SerializationFormat} of the {@link Request}.
     *
     * @throws RequestLogAvailabilityException if this property is not available yet
     */
    SerializationFormat serializationFormat();

    /**
     * Returns the {@link Scheme} of the {@link Request}.
     *
     * @throws RequestLogAvailabilityException if this property is not available yet
     */
    Scheme scheme();

    /**
     * Returns the host name of the {@link Request}.
     *
     * @return the host name. {@code null} if the {@link Request} has failed even before it is started.
     * @throws RequestLogAvailabilityException if this property is not available yet
     */
    @Nullable
    String host();

    /**
     * Returns the status of the {@link Response}.
     *
     * @return the {@link HttpStatus}. {@code null} if the {@link Response} has failed even before receiving
     *         its first non-informational headers.
     * @throws RequestLogAvailabilityException if this property is not available yet
     */
    @Nullable
    default HttpStatus status() {
        return responseHeaders().status();
    }

    /**
     * Returns the status code of the {@link Response}.
     *
     * @return the integer value of the {@link #status()}.
     *         {@code -1} if {@link #status()} returns {@code null}.
     */
    default int statusCode() {
        final HttpStatus status = status();
        return status != null ? status.code() : -1;
    }

    /**
     * Returns the {@link HttpHeaders} of the {@link Request}.
     *
     * @throws RequestLogAvailabilityException if this property is not available yet
     */
    HttpHeaders requestHeaders();

    /**
     * Returns the high-level content object of the {@link Request}, which is specific
     * to the {@link SerializationFormat}.
     *
     * @return {@link RpcRequest} for RPC, or {@code null} for others
     * @throws RequestLogAvailabilityException if this property is not available yet
     */
    @Nullable
    Object requestContent();

    /**
     * Returns the low-level content object of the {@link Request}, which is specific
     * to the {@link SerializationFormat}.
     *
     * @return {@code ThriftCall} for Thrift, or {@code null} for others
     * @throws RequestLogAvailabilityException if this property is not available yet
     */
    @Nullable
    Object rawRequestContent();

    /**
     * Returns the non-informational status {@link HttpHeaders} of the {@link Response}.
     *
     * @throws RequestLogAvailabilityException if this property is not available yet
     */
    HttpHeaders responseHeaders();

    /**
     * Returns the high-level content object of the {@link Response}, which is specific
     * to the {@link SerializationFormat}.
     *
     * @return {@link RpcResponse} for RPC, or {@code null} for others
     * @throws RequestLogAvailabilityException if this property is not available yet
     */
    @Nullable
    Object responseContent();

    /**
     * Returns the low-level content object of the {@link Response}, which is specific
     * to the {@link SerializationFormat}.
     *
     * @return {@code ThriftReply} for Thrift, or {@code null} for others
     * @throws RequestLogAvailabilityException if this property is not available yet
     */
    @Nullable
    Object rawResponseContent();

    /**
     * Returns the string representation of the {@link Request}, with no sanitization of headers or content.
     */
    String toStringRequestOnly();

    /**
     * Returns the string representation of the {@link Request}.
     *
     * @param headersSanitizer a {@link Function} for sanitizing HTTP headers for logging. The result of the
     *     {@link Function} is what is actually logged as headers.
     * @param contentSanitizer a {@link Function} for sanitizing request content for logging. The result of the
     *     {@link Function} is what is actually logged as content.
     */
    String toStringRequestOnly(Function<HttpHeaders, HttpHeaders> headersSanitizer,
                               Function<Object, Object> contentSanitizer);

    /**
     * Returns the string representation of the {@link Response}, with no sanitization of headers or content.
     */
    String toStringResponseOnly();

    /**
     * Returns the string representation of the {@link Response}.
     *
     * @param headersSanitizer a {@link Function} for sanitizing HTTP headers for logging. The result of the
     *     {@link Function} is what is actually logged as headers.
     * @param contentSanitizer a {@link Function} for sanitizing response content for logging. The result of the
     *     {@link Function} is what is actually logged as content.
     */
    String toStringResponseOnly(Function<HttpHeaders, HttpHeaders> headersSanitizer,
                                Function<Object, Object> contentSanitizer);
}
