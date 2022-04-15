/*
 * Copyright 2017 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Holds the parameters which are required to find a service available to handle the request.
 */
public interface RoutingContext {

    /**
     * Returns the {@link VirtualHost} instance which belongs to this {@link RoutingContext}.
     */
    VirtualHost virtualHost();

    /**
     * Returns the virtual host name of the request.
     */
    String hostname();

    /**
     * Returns {@link HttpMethod} of the request.
     */
    HttpMethod method();

    /**
     * Returns the absolute path retrieved from the request,
     * as defined in <a href="https://datatracker.ietf.org/doc/rfc3986/">RFC3986</a>.
     */
    String path();

    /**
     * Returns the query retrieved from the request,
     * as defined in <a href="https://datatracker.ietf.org/doc/rfc3986/">RFC3986</a>.
     */
    @Nullable
    String query();

    /**
     * Returns the query parameters retrieved from the request path.
     */
    QueryParams params();

    /**
     * Returns {@link MediaType} specified by 'Content-Type' header of the request.
     */
    @Nullable
    MediaType contentType();

    /**
     * Returns a list of {@link MediaType}s that are specified in {@link HttpHeaderNames#ACCEPT} in the order
     * of client-side preferences. If the client does not send the header, this will contain only
     * {@link MediaType#ANY_TYPE}.
     */
    List<MediaType> acceptTypes();

    /**
     * Returns the {@link RequestHeaders} retrieved from the request.
     */
    RequestHeaders headers();

    /**
     * Returns the {@link RoutingStatus} of the request.
     */
    @UnstableApi
    RoutingStatus status();

    /**
     * Defers throwing an {@link HttpStatusException} until reaching the end of the service list.
     */
    void deferStatusException(HttpStatusException cause);

    /**
     * Returns a deferred {@link HttpStatusException} which was previously set via
     * {@link #deferStatusException(HttpStatusException)}.
     */
    @Nullable
    HttpStatusException deferredStatusException();

    /**
     * Returns a wrapped {@link RoutingContext} which holds the specified {@code path}.
     * It is usually used to find an {@link HttpService} with a prefix-stripped path.
     */
    default RoutingContext overridePath(String path) {
        requireNonNull(path, "path");
        return new RoutingContextWrapper(this) {
            @Override
            public String path() {
                return path;
            }
        };
    }

    /**
     * Returns {@code true} if this context is for a CORS preflight request.
     *
     * @deprecated Use {@link #status()} and {@link RoutingStatus#CORS_PREFLIGHT}.
     */
    @Deprecated
    boolean isCorsPreflight();

    /**
     * Returns {@code true} if this context requires matching the predicates for query parameters.
     *
     * @see RouteBuilder#matchesParams(Iterable)
     */
    default boolean requiresMatchingParamsPredicates() {
        return true;
    }

    /**
     * Returns {@code true} if this context requires matching the predicates for HTTP headers.
     *
     * @see RouteBuilder#matchesHeaders(Iterable)
     */
    default boolean requiresMatchingHeadersPredicates() {
        return true;
    }
}
