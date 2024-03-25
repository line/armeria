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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Objects;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Holds the parameters which are required to find a service available to handle the request.
 */
final class DefaultRoutingContext implements RoutingContext {

    /**
     * Returns a new {@link RoutingContext} instance.
     */
    static RoutingContext of(VirtualHost virtualHost, String hostname, RequestTarget reqTarget,
                             RequestHeaders headers, RoutingStatus routingStatus,
                             SessionProtocol sessionProtocol) {
        return new DefaultRoutingContext(virtualHost, hostname, headers, reqTarget, routingStatus,
                                         sessionProtocol);
    }

    private final VirtualHost virtualHost;
    private final String hostname;
    private final HttpMethod method;
    private final RequestHeaders headers;
    private final RequestTarget reqTarget;
    @Nullable
    private final MediaType contentType;
    private final List<MediaType> acceptTypes;
    private final RoutingStatus routingStatus;
    private final SessionProtocol sessionProtocol;
    @Nullable
    private volatile QueryParams queryParams;
    @Nullable
    private HttpStatusException deferredCause;
    @Nullable
    private Routed<ServiceConfig> result;

    private final int hashCode;

    DefaultRoutingContext(VirtualHost virtualHost, String hostname, RequestHeaders headers,
                          RequestTarget reqTarget, RoutingStatus routingStatus,
                          SessionProtocol sessionProtocol) {
        this.virtualHost = requireNonNull(virtualHost, "virtualHost");
        this.hostname = requireNonNull(hostname, "hostname");
        this.headers = requireNonNull(headers, "headers");
        this.reqTarget = requireNonNull(reqTarget, "reqTarget");
        this.routingStatus = routingStatus;
        this.sessionProtocol = sessionProtocol;
        method = headers.method();
        contentType = headers.contentType();
        acceptTypes = headers.accept();
        hashCode = hashCode(this);
    }

    @Override
    public VirtualHost virtualHost() {
        return virtualHost;
    }

    @Override
    public String hostname() {
        return hostname;
    }

    @Override
    public HttpMethod method() {
        return method;
    }

    @Override
    public RequestTarget requestTarget() {
        return reqTarget;
    }

    @Override
    public String path() {
        return reqTarget.path();
    }

    @Nullable
    @Override
    public String query() {
        return reqTarget.query();
    }

    @Override
    public QueryParams params() {
        QueryParams queryParams = this.queryParams;
        if (queryParams == null) {
            final String query = reqTarget.query();
            if (query == null) {
                queryParams = QueryParams.of();
            } else {
                queryParams = QueryParams.fromQueryString(query);
            }
            this.queryParams = queryParams;
        }
        return queryParams;
    }

    @Nullable
    @Override
    public MediaType contentType() {
        return contentType;
    }

    @Override
    public List<MediaType> acceptTypes() {
        return acceptTypes;
    }

    @Override
    public boolean isCorsPreflight() {
        return status() == RoutingStatus.CORS_PREFLIGHT;
    }

    @Override
    public RequestHeaders headers() {
        return headers;
    }

    @Override
    public RoutingStatus status() {
        return routingStatus;
    }

    @Override
    public SessionProtocol sessionProtocol() {
        return sessionProtocol;
    }

    @Override
    public void deferStatusException(HttpStatusException deferredCause) {
        // Update with the last cause
        this.deferredCause = requireNonNull(deferredCause, "deferredCause");
    }

    @Override
    public HttpStatusException deferredStatusException() {
        return deferredCause;
    }

    @Override
    public boolean hasResult() {
        return result != null;
    }

    @Override
    public void setResult(Routed<ServiceConfig> result) {
        requireNonNull(result, "result");
        checkState(this.result == null, "result is set already.");
        this.result = result;
    }

    @Override
    public Routed<ServiceConfig> result() {
        checkState(result != null, "result has not set yet.");
        return result;
    }

    // For hashing and comparison, we use these properties of the context
    // 0 : VirtualHost
    // 1 : HttpMethod
    // 2 : Path
    // 3 : Content-Type
    // 4 : Accept
    //
    // Note that we don't use query(), params(), header() and result() for generating hashCode and comparing
    // objects, because this class can be cached in RouteCache class. Using all properties may cause cache
    // misses from RouteCache so it would be better if we choose the properties which can be a cache key.

    @Override
    public int hashCode() {
        return hashCode;
    }

    static int hashCode(RoutingContext routingCtx) {
        int result = routingCtx.virtualHost().hashCode();
        result *= 31;
        result += routingCtx.method().hashCode();
        result *= 31;
        result += routingCtx.path().hashCode();
        result *= 31;
        result += Objects.hashCode(routingCtx.contentType());
        for (MediaType mediaType : routingCtx.acceptTypes()) {
            result *= 31;
            result += mediaType.hashCode();
        }
        return result;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        return equals(this, obj);
    }

    static boolean equals(RoutingContext self, @Nullable Object obj) {
        if (self == obj) {
            return true;
        }

        if (!(obj instanceof RoutingContext)) {
            return false;
        }

        final RoutingContext other = (RoutingContext) obj;
        return self.virtualHost().equals(other.virtualHost()) &&
               self.method() == other.method() &&
               self.path().equals(other.path()) &&
               Objects.equals(self.contentType(), other.contentType()) &&
               self.acceptTypes().equals(other.acceptTypes());
    }

    @Override
    public String toString() {
        return toString(this);
    }

    static String toString(RoutingContext routingCtx) {
        final ToStringHelper helper = MoreObjects.toStringHelper(routingCtx)
                                                 .omitNullValues()
                                                 .add("virtualHost", routingCtx.virtualHost())
                                                 .add("method", routingCtx.method())
                                                 .add("path", routingCtx.path())
                                                 .add("query", routingCtx.query())
                                                 .add("contentType", routingCtx.contentType())
                                                 .add("status", routingCtx.status());
        if (routingCtx.hasResult()) {
            helper.add("result", routingCtx.result());
        }
        if (!routingCtx.acceptTypes().isEmpty()) {
            helper.add("acceptTypes", routingCtx.acceptTypes());
        }
        helper.add("requiresMatchingParamsPredicates", routingCtx.requiresMatchingParamsPredicates())
              .add("requiresMatchingHeadersPredicates", routingCtx.requiresMatchingHeadersPredicates());
        return helper.toString();
    }
}
