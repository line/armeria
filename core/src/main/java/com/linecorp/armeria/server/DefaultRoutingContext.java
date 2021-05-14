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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;

/**
 * Holds the parameters which are required to find a service available to handle the request.
 */
final class DefaultRoutingContext implements RoutingContext {

    private static final Logger logger = LoggerFactory.getLogger(DefaultRoutingContext.class);

    private static final Splitter ACCEPT_SPLITTER = Splitter.on(',').trimResults();

    /**
     * Returns a new {@link RoutingContext} instance.
     */
    static RoutingContext of(VirtualHost virtualHost, String hostname,
                             String path, @Nullable String query,
                             RequestHeaders headers, boolean isCorsPreflight) {
        return new DefaultRoutingContext(virtualHost, hostname, headers, path, query, isCorsPreflight);
    }

    private final VirtualHost virtualHost;
    private final String hostname;
    private final HttpMethod method;
    private final RequestHeaders headers;
    private final String path;
    @Nullable
    private final String query;
    @Nullable
    private final MediaType contentType;
    private final List<MediaType> acceptTypes;
    @Nullable
    private volatile QueryParams queryParams;
    private final boolean isCorsPreflight;
    @Nullable
    private HttpStatusException deferredCause;

    private final int hashCode;

    DefaultRoutingContext(VirtualHost virtualHost, String hostname, RequestHeaders headers,
                          String path, @Nullable String query, boolean isCorsPreflight) {
        this.virtualHost = requireNonNull(virtualHost, "virtualHost");
        this.hostname = requireNonNull(hostname, "hostname");
        this.headers = requireNonNull(headers, "headers");
        this.path = requireNonNull(path, "path");
        this.query = query;
        this.isCorsPreflight = isCorsPreflight;
        method = headers.method();
        contentType = headers.contentType();
        acceptTypes = extractAcceptTypes(headers);
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
    public String path() {
        return path;
    }

    @Nullable
    @Override
    public String query() {
        return query;
    }

    @Override
    public QueryParams params() {
        QueryParams queryParams = this.queryParams;
        if (queryParams == null) {
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
        return isCorsPreflight;
    }

    @Override
    public RequestHeaders headers() {
        return headers;
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

    // For hashing and comparison, we use these properties of the context
    // 0 : VirtualHost
    // 1 : HttpMethod
    // 2 : Path
    // 3 : Content-Type
    // 4 : Accept
    //
    // Note that we don't use query(), params() and header() for generating hashCode and comparing objects,
    // because this class can be cached in RouteCache class. Using all properties may cause cache misses
    // from RouteCache so it would be better if we choose the properties which can be a cache key.

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
                                                 .add("contentType", routingCtx.contentType());
        if (!routingCtx.acceptTypes().isEmpty()) {
            helper.add("acceptTypes", routingCtx.acceptTypes());
        }
        helper.add("isCorsPreflight", routingCtx.isCorsPreflight())
              .add("requiresMatchingParamsPredicates", routingCtx.requiresMatchingParamsPredicates())
              .add("requiresMatchingHeadersPredicates", routingCtx.requiresMatchingHeadersPredicates());
        return helper.toString();
    }

    @VisibleForTesting
    static List<MediaType> extractAcceptTypes(HttpHeaders headers) {
        final List<String> acceptHeaders = headers.getAll(HttpHeaderNames.ACCEPT);
        if (acceptHeaders.isEmpty()) {
            // No 'Accept' header means accepting everything.
            return ImmutableList.of();
        }

        final List<MediaType> acceptTypes = new ArrayList<>(4);
        for (String acceptHeader : acceptHeaders) {
            for (String mediaType : ACCEPT_SPLITTER.split(acceptHeader)) {
                try {
                    acceptTypes.add(MediaType.parse(mediaType));
                } catch (IllegalArgumentException e) {
                    logger.debug("Ignoring a malformed media type from 'accept' header: {}",
                                 mediaType);
                }
            }
        }

        if (acceptTypes.isEmpty()) {
            return ImmutableList.of();
        }

        if (acceptTypes.size() > 1) {
            acceptTypes.sort(DefaultRoutingContext::compareMediaType);
        }
        return ImmutableList.copyOf(acceptTypes);
    }

    @VisibleForTesting
    static int compareMediaType(MediaType m1, MediaType m2) {
        // The order should be "q=1.0, q=0.5".
        // To ensure descending order, we pass the q values of m2 and m1 respectively.
        final int qCompare = Float.compare(m2.qualityFactor(), m1.qualityFactor());
        if (qCompare != 0) {
            return qCompare;
        }
        // The order should be "application/*, */*".
        final int wildcardCompare = Integer.compare(m1.numWildcards(), m2.numWildcards());
        if (wildcardCompare != 0) {
            return wildcardCompare;
        }
        // Finally, sort by lexicographic order. ex, application/*, image/*
        return m1.type().compareTo(m2.type());
    }
}
