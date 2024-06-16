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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.internal.common.DefaultRequestTarget.removeMatrixVariables;
import static java.util.Objects.requireNonNull;

import java.util.List;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.RequestTargetForm;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.DefaultRequestTarget;

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
     * Returns a wrapped {@link RoutingContext} which holds the specified {@link HttpMethod}.
     */
    @UnstableApi
    default RoutingContext withMethod(HttpMethod method) {
        requireNonNull(method, "method");
        if (method == method()) {
            return this;
        }
        return new RoutingContextWrapper(this) {
            @Override
            public HttpMethod method() {
                return method;
            }
        };
    }

    /**
     * Returns the {@link RequestTarget} of the request. The form of the returned {@link RequestTarget} is
     * never {@link RequestTargetForm#ABSOLUTE}, which means it is always {@link RequestTargetForm#ORIGIN} or
     * {@link RequestTargetForm#ASTERISK}.
     */
    RequestTarget requestTarget();

    /**
     * Returns the absolute path retrieved from the request,
     * as defined in <a href="https://datatracker.ietf.org/doc/rfc3986/">RFC3986</a>.
     */
    default String path() {
        return requestTarget().path();
    }

    /**
     * Returns the query retrieved from the request,
     * as defined in <a href="https://datatracker.ietf.org/doc/rfc3986/">RFC3986</a>.
     */
    @Nullable
    default String query() {
        return requestTarget().query();
    }

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
     * Returns the {@link SessionProtocol} of the request.
     */
    @UnstableApi
    SessionProtocol sessionProtocol();

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
     * (Advanced users only) Returns a wrapped {@link RoutingContext} which holds the specified {@code path}.
     * It is usually used to find an {@link HttpService} with a prefix-stripped path.
     * Note that specifying a malformed or relative path will lead to unspecified behavior.
     */
    default RoutingContext withPath(String path) {
        requireNonNull(path, "path");
        final String pathWithoutMatrixVariables;
        if (Flags.allowSemicolonInPathComponent()) {
            pathWithoutMatrixVariables = path;
        } else {
            pathWithoutMatrixVariables = removeMatrixVariables(path);
            checkArgument(pathWithoutMatrixVariables != null,
                          "path with invalid matrix variables: %s", path);
        }

        final RequestTarget oldReqTarget = requestTarget();
        final RequestTarget newReqTarget =
                DefaultRequestTarget.createWithoutValidation(
                        oldReqTarget.form(),
                        oldReqTarget.scheme(),
                        oldReqTarget.authority(),
                        oldReqTarget.host(),
                        oldReqTarget.port(),
                        pathWithoutMatrixVariables,
                        path,
                        oldReqTarget.query(),
                        oldReqTarget.fragment());

        return new RoutingContextWrapper(this) {
            @Override
            public RequestTarget requestTarget() {
                return newReqTarget;
            }
        };
    }

    /**
     * Returns a wrapped {@link RoutingContext} which holds the specified {@code path}.
     * It is usually used to find an {@link HttpService} with a prefix-stripped path.
     *
     * @deprecated Use {@link #withPath(String)}.
     */
    @Deprecated
    default RoutingContext overridePath(String path) {
        return withPath(path);
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

    /**
     * Returns {@code true} if a {@linkplain Routed routing result} is set.
     */
    @UnstableApi
    boolean hasResult();

    /**
     * Sets the {@linkplain Routed routing result} to this {@link RoutingContext}.
     *
     * <p>Note that the result is automatically set when this {@link RoutingContext} finds a matched
     * {@link ServiceConfig} from a {@link Router}. Don't set a {@code result} manually unless you really
     * intend it.
     *
     * @throws IllegalStateException if a {@link Routed} is set already.
     */
    @UnstableApi
    void setResult(Routed<ServiceConfig> result);

    /**
     * Returns the {@linkplain Routed routing result} of this {@link RoutingContext}.
     *
     * @throws IllegalStateException if the result has not resolved yet.
     */
    @UnstableApi
    Routed<ServiceConfig> result();
}
