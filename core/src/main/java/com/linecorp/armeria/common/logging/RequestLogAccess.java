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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;

/**
 * Provides the access to a {@link RequestLog} or {@link RequestOnlyLog}, while ensuring the interested
 * {@link RequestLogProperty}s are available.
 *
 * <p>The properties provided by {@link RequestLog} are not always fully available. Use the following
 * methods to access the properties safely:
 * <ul>
 *   <li>{@link #isComplete()} or {@link #whenComplete()} to check if or to get notified when all request
 *       and response properties are available.</li>
 *   <li>{@link #isRequestComplete()} or {@link #whenRequestComplete()} to check if or to get notified when
 *       all request properties are available.</li>
 *   <li>{@link #isAvailable(RequestLogProperty)}, {@link #isAvailable(RequestLogProperty...)},
 *       {@link #isAvailable(Iterable)}, {@link #whenAvailable(RequestLogProperty)},
 *       {@link #whenAvailable(RequestLogProperty...)} or {@link #whenAvailable(Iterable)} to check if or
 *       to get notified when a certain set of properties are available.</li>
 * </ul>
 *
 * <p>If you are sure that certain properties are available, you can convert a {@link RequestLogAccess} into
 * a {@link RequestLog} or {@link RequestOnlyLog} by using the {@code "ensure*()"} methods, such as
 * {@link #ensureComplete()} and {@link #ensureRequestComplete()}.</p>
 */
public interface RequestLogAccess {

    /**
     * Returns {@code true} if the {@link Request} has been processed completely and thus all properties of
     * the {@link RequestLog} have been collected.
     */
    boolean isComplete();

    /**
     * Returns {@code true} if the {@link Request} has been consumed completely and thus all properties of
     * the {@link RequestOnlyLog} have been collected.
     */
    boolean isRequestComplete();

    /**
     * Returns {@code true} if the specified {@link RequestLogProperty} is available.
     */
    boolean isAvailable(RequestLogProperty property);

    /**
     * Returns {@code true} if all of the specified {@link RequestLogProperty}s are available.
     *
     * @throws IllegalArgumentException if {@code properties} is empty.
     */
    boolean isAvailable(RequestLogProperty... properties);

    /**
     * Returns {@code true} if all of the specified {@link RequestLogProperty}s are available.
     *
     * @throws IllegalArgumentException if {@code properties} is empty.
     */
    boolean isAvailable(Iterable<RequestLogProperty> properties);

    /**
     * Returns a {@link CompletableFuture} which will be completed when the {@link Request} has been processed
     * completely and thus all properties of the {@link RequestLog} have been collected.
     * The returned {@link CompletableFuture} is never completed exceptionally.
     * <pre>{@code
     * logAccess.whenComplete().thenAccept(log -> {
     *     HttpStatus status = log.responseHeaders().status();
     *     if (status == HttpStatus.OK) {
     *         ...
     *     }
     * });
     * }</pre>
     */
    CompletableFuture<RequestLog> whenComplete();

    /**
     * Returns a {@link CompletableFuture} which will be completed when the {@link Request} has been consumed
     * completely and thus all properties of the {@link RequestOnlyLog} have been collected.
     * The returned {@link CompletableFuture} is never completed exceptionally.
     * <pre>{@code
     * logAccess.whenRequestComplete().thenAccept(log -> {
     *     SerializationFormat serFmt = log.scheme().serializationFormat();
     *     if (serFmt == ThriftSerializationFormats.BINARY) {
     *         ...
     *     }
     * });
     * }</pre>
     */
    CompletableFuture<RequestOnlyLog> whenRequestComplete();

    /**
     * Returns a {@link CompletableFuture} which will be completed when the specified
     * {@link RequestLogProperty} is collected. The returned {@link CompletableFuture} is never completed
     * exceptionally. Note that the completion of the returned {@link CompletableFuture} guarantees only
     * the availability of the specified property, which means any attempt to access other properties than
     * specified may trigger a {@link RequestLogAvailabilityException}.
     * If in doubt, use {@link #whenComplete()} or {@link #whenRequestComplete()}.
     * <pre>{@code
     * logAccess.whenAvailable(RequestLogProperty.REQUEST_HEADERS)
     *          .thenAccept(log -> {
     *              RequestHeaders headers = log.requestHeaders();
     *              if (headers.path().startsWith("/foo/")) {
     *                  ...
     *              }
     *          });
     * }</pre>
     */
    CompletableFuture<RequestLog> whenAvailable(RequestLogProperty property);

    /**
     * Returns a {@link CompletableFuture} which will be completed when all the specified
     * {@link RequestLogProperty}s are collected. The returned {@link CompletableFuture} is never completed
     * exceptionally. Note that the completion of the returned {@link CompletableFuture} guarantees only
     * the availability of the specified properties, which means any attempt to access other properties than
     * specified may trigger a {@link RequestLogAvailabilityException}.
     * If in doubt, use {@link #whenComplete()} or {@link #whenRequestComplete()}.
     * <pre>{@code
     * logAccess.whenAvailable(RequestLogProperty.REQUEST_HEADERS,
     *                         RequestLogProperty.RESPONSE_HEADERS)
     *          .thenAccept(log -> {
     *              RequestHeaders reqHeaders = log.requestHeaders();
     *              ResponseHeaders resHeaders = log.responseHeaders();
     *              if (reqHeaders.path().startsWith("/foo/") &&
     *                  resHeaders.status() == HttpStatus.OK) {
     *                  ...
     *              }
     *          });
     * }</pre>
     *
     * @throws IllegalArgumentException if {@code properties} is empty.
     */
    CompletableFuture<RequestLog> whenAvailable(RequestLogProperty... properties);

    /**
     * Returns a {@link CompletableFuture} which will be completed when all the specified
     * {@link RequestLogProperty}s are collected. The returned {@link CompletableFuture} is never completed
     * exceptionally. Note that the completion of the returned {@link CompletableFuture} guarantees only
     * the availability of the specified properties, which means any attempt to access other properties than
     * specified may trigger a {@link RequestLogAvailabilityException}.
     * If in doubt, use {@link #whenComplete()} or {@link #whenRequestComplete()}.
     * <pre>{@code
     * logAccess.whenAvailable(Lists.of(RequestLogProperty.REQUEST_HEADERS,
     *                                  RequestLogProperty.RESPONSE_HEADERS))
     *          .thenAccept(log -> {
     *              RequestHeaders reqHeaders = log.requestHeaders();
     *              ResponseHeaders resHeaders = log.responseHeaders();
     *              if (reqHeaders.path().startsWith("/foo/") &&
     *                  resHeaders.status() == HttpStatus.OK) {
     *                  ...
     *              }
     *          });
     * }</pre>
     *
     * @throws IllegalArgumentException if {@code properties} is empty.
     */
    CompletableFuture<RequestLog> whenAvailable(Iterable<RequestLogProperty> properties);

    /**
     * Returns the {@link RequestLog} that is guaranteed to have all properties, for both request and response
     * side.
     *
     * @throws RequestLogAvailabilityException if the {@link Request} was not fully processed yet.
     */
    RequestLog ensureComplete();

    /**
     * Returns the {@link RequestLog} that is guaranteed to have all request-side properties.
     *
     * @throws RequestLogAvailabilityException if the {@link Request} was not fully consumed yet.
     */
    RequestOnlyLog ensureRequestComplete();

    /**
     * Returns the {@link RequestLog} that is guaranteed to have the specified {@link RequestLogProperty}.
     *
     * @throws RequestLogAvailabilityException if the specified {@link RequestLogProperty} is not available yet.
     */
    RequestLog ensureAvailable(RequestLogProperty property);

    /**
     * Returns the {@link RequestLog} that is guaranteed to have all the specified {@link RequestLogProperty}s.
     *
     * @throws RequestLogAvailabilityException if any of the specified {@link RequestLogProperty}s are
     *                                         not available yet.
     *
     * @throws IllegalArgumentException if {@code properties} is empty.
     */
    RequestLog ensureAvailable(RequestLogProperty... properties);

    /**
     * Returns the {@link RequestLog} that is guaranteed to have all the specified {@link RequestLogProperty}s.
     *
     * @throws RequestLogAvailabilityException if any of the specified {@link RequestLogProperty}s are
     *                                         not available yet.
     *
     * @throws IllegalArgumentException if {@code properties} is empty.
     */
    RequestLog ensureAvailable(Iterable<RequestLogProperty> properties);

    /**
     * Returns the {@link RequestLog} for the {@link Request}, where all properties may not be available yet.
     * Note that this method is potentially unsafe; an attempt to access an unavailable property will trigger
     * a {@link RequestLogAvailabilityException}. If in doubt, use {@link #whenComplete()} or
     * {@link #whenRequestComplete()}. Always consider guarding the property access with
     * {@link #isAvailable(RequestLogProperty)} when you have to use this method:
     * <pre>{@code
     * RequestLogAccess logAccess = ...;
     * if (logAccess.isAvailable(RequestLogProperty.REQUEST_HEADERS)) {
     *     RequestHeaders headers = logAccess.partial().requestHeaders();
     *     ...
     * }
     * }</pre>
     */
    RequestLog partial();

    /**
     * Returns an {@code int} representation of the currently available properties of this {@link RequestLog}.
     * This can be useful when needing to quickly compare the availability of the {@link RequestLog} during
     * the processing of the request. Use {@link #isAvailable(RequestLogProperty)} to actually check
     * availability.
     */
    int availabilityStamp();

    /**
     * Returns the {@link RequestContext} associated with the {@link Request} being handled.
     *
     * <p>This method always returns non-{@code null} regardless of what properties are currently available.
     */
    RequestContext context();

    /**
     * Returns the {@link RequestLogAccess} that provides access to the parent {@link RequestLog}.
     * {@code null} is returned if the {@link RequestLog} was not added as a child log.
     *
     * @see RequestLogBuilder#addChild(RequestLogAccess)
     */
    @Nullable
    RequestLogAccess parent();

    /**
     * Returns the list of {@link RequestLogAccess}es that provide access to the child {@link RequestLog}s,
     * ordered by the time they were added.
     */
    List<RequestLogAccess> children();
}
