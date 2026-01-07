/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
 *
 */

package com.linecorp.armeria.server.athenz.resource;

import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.core.JsonPointer;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Provides the Athenz resource string from an HTTP request.
 *
 * <p>This is the core interface for extracting resource identifiers from various parts of an HTTP request
 * (path, headers, JSON body fields, etc.) for Athenz authorization.
 *
 * <p>Built-in implementations are available through static factory methods:
 * - {@link #ofPath()} - Extracts resource from the request path
 * - {@link #ofPath(boolean)} - Extracts resource from the request path, optionally including query parameters
 * - {@link #ofHeader(String)} - Extracts resource from a specific HTTP header
 * - {@link #ofJsonField(String)} - Extracts resource from a JSON body field
 * - {@link #ofJsonField(JsonPointer)} - Extracts resource from a JSON body field using JSON Pointer
 *
 * <p>Example usage with path-based resource:
 * <pre>{@code
 * AthenzService.builder(ztsBaseClient)
 *              .resourceProvider(AthenzResourceProvider.ofPath())
 *              .action("read")
 *              .newDecorator();
 * }</pre>
 *
 * <p>Example usage with header-based resource:
 * <pre>{@code
 * AthenzService.builder(ztsBaseClient)
 *              .resourceProvider(AthenzResourceProvider.ofHeader("X-Resource-Id"))
 *              .action("write")
 *              .newDecorator();
 * }</pre>
 *
 * <p>Custom implementations can be created by implementing the
 * {@link #provide(ServiceRequestContext, HttpRequest)} method to extract resources from any part of
 * the request.
 */
@UnstableApi
@FunctionalInterface
public interface AthenzResourceProvider {

    /**
     * Provides the Athenz resource string from the given HTTP request.
     *
     * @param ctx the service request context
     * @param req the HTTP request
     * @return a {@link CompletableFuture} that completes with the resource string
     */
    CompletableFuture<String> provide(ServiceRequestContext ctx, HttpRequest req);

    /**
     * Returns an {@link AthenzResourceProvider} that extracts the resource from the request path
     * without query parameters.
     *
     * <p>The provider reads the normalized path from {@link ServiceRequestContext#path()} and returns it
     * as the Athenz resource string. Query parameters are excluded. The returned
     * {@link CompletableFuture} is completed immediately with the path value.
     *
     * <p>Example:
     * <pre>{@code
     * // For a request to "/api/users/123?status=active", the resource is "/api/users/123".
     * AthenzService.builder(ztsBaseClient)
     *              .resourceProvider(AthenzResourceProvider.ofPath())
     *              .action("read")
     *              .newDecorator();
     * }</pre>
     *
     * @return an {@link AthenzResourceProvider} that provides the request path as the resource
     */
    static AthenzResourceProvider ofPath() {
        return PathAthenzResourceProvider.INSTANCE;
    }

    /**
     * Returns an {@link AthenzResourceProvider} that extracts the resource from the request path,
     * optionally including query parameters.
     *
     * <p>When {@code includeQuery} is {@code true}, the provider returns the raw path from
     * {@link HttpRequest#path()} which includes query parameters.
     * When {@code false}, it uses the normalized path from {@link ServiceRequestContext#path()}
     * which excludes query parameters.
     *
     * <p>The returned {@link CompletableFuture} is completed immediately with the path value.
     *
     * <p>Example:
     * - Request: {@code "/api/users/123?status=active"}
     * - {@code includeQuery = false} → resource: {@code "/api/users/123"}
     * - {@code includeQuery = true} → resource: {@code "/api/users/123?status=active"}
     *
     * @param includeQuery whether to include query parameters in the resource string
     * @return an {@link AthenzResourceProvider} that provides the request path as the resource
     */
    static AthenzResourceProvider ofPath(boolean includeQuery) {
        if (includeQuery) {
            return PathAthenzResourceProvider.QUERY_INSTANCE;
        } else {
            return PathAthenzResourceProvider.INSTANCE;
        }
    }

    /**
     * Returns an {@link AthenzResourceProvider} that extracts the Athenz resource string
     * from the specified HTTP header in the request.
     *
     * <p>This provider reads the header value for the given name from the incoming request.
     * If the header is missing or its value is empty, an {@link IllegalArgumentException} is thrown.
     *
     * <p>Example:
     * <pre>{@code
     * AthenzService.builder(ztsBaseClient)
     *              .resourceProvider(AthenzResourceProvider.ofHeader("X-Resource-Id"))
     *              .action("read")
     *              .newDecorator();
     * }</pre>
     *
     * @param headerName the HTTP header name to read the resource from; must not be {@code null} or empty
     * @return an {@link AthenzResourceProvider} that reads the resource from the specified header
     * @throws NullPointerException if {@code headerName} is {@code null}
     * @throws IllegalArgumentException if {@code headerName} is empty
     */
    static AthenzResourceProvider ofHeader(String headerName) {
        return new HeaderAthenzResourceProvider(headerName);
    }

    /**
     * Returns an {@link AthenzResourceProvider} that extracts the resource from a JSON request body field
     * using the given {@link JsonPointer}.
     *
     * <p>This provider aggregates the request, parses the body as JSON, and reads the value at the
     * specified JSON Pointer.
     *
     * <p>Example:
     * <pre>{@code
     * JsonPointer pointer = JsonPointer.compile("/user/id");
     * AthenzService.builder(ztsBaseClient)
     *              .resourceProvider(AthenzResourceProvider.ofJsonField(pointer))
     *              .action("read")
     *              .newDecorator();
     * }</pre>
     *
     * @param jsonPointer the JSON Pointer for the field containing the resource; must not be {@code null}
     * @return an {@link AthenzResourceProvider} that reads the resource from the specified JSON field
     */
    static AthenzResourceProvider ofJsonField(JsonPointer jsonPointer) {
        return new JsonBodyFieldAthenzResourceProvider(jsonPointer);
    }

    /**
     * Returns an {@link AthenzResourceProvider} that extracts the resource from a JSON request body field
     * using the given JSON Pointer expression string.
     *
     * <p>This provider aggregates the request, parses the body as JSON, and reads the value at the
     * specified JSON Pointer.
     *
     * <p>{@code jsonPointerExpr} must be a JSON Pointer expression as defined in RFC 6901, starting with
     * {@code '/'} (e.g., {@code "/user/id"}).
     *
     * <p>Example:
     * <pre>{@code
     * AthenzService.builder(ztsBaseClient)
     *              .resourceProvider(AthenzResourceProvider.ofJsonField("/user/id"))
     *              .action("read")
     *              .newDecorator();
     * }</pre>
     *
     * @param jsonPointerExpr the JSON Pointer expression string (RFC 6901); must not be {@code null} and
     *                        must start with {@code '/'}
     * @return an {@link AthenzResourceProvider} that reads the resource from the specified JSON field
     */
    static AthenzResourceProvider ofJsonField(String jsonPointerExpr) {
        return new JsonBodyFieldAthenzResourceProvider(jsonPointerExpr);
    }
}
