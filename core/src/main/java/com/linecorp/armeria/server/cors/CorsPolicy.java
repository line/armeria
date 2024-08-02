/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.server.cors;

import static com.linecorp.armeria.internal.server.CorsHeaderUtil.DELIMITER;
import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.HttpTimestampSupplier;
import com.linecorp.armeria.internal.server.CorsHeaderUtil;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.cors.CorsConfig.ConstantValueSupplier;

import io.netty.util.AsciiString;

/**
 * Contains information of the CORS policy with the specified origins.
 */
public final class CorsPolicy {

    /**
     * Returns a new {@link CorsPolicyBuilder}.
     */
    public static CorsPolicyBuilder builder() {
        return new CorsPolicyBuilder();
    }

    /**
     * Returns a new {@link CorsPolicyBuilder} with the specified {@code origins}.
     */
    public static CorsPolicyBuilder builder(String... origins) {
        return new CorsPolicyBuilder(origins);
    }

    /**
     * Returns a new {@link CorsPolicyBuilder} with the specified {@code origins}.
     */
    public static CorsPolicyBuilder builder(Iterable<String> origins) {
        return new CorsPolicyBuilder(origins);
    }

    /**
     * Returns a new {@link CorsPolicyBuilder} with origins matching the {@code predicate}.
     */
    public static CorsPolicyBuilder builder(Predicate<? super String> predicate) {
        return new CorsPolicyBuilder(predicate);
    }

    /**
     * Returns a new {@link CorsPolicyBuilder} with origins matching the {@code regex}.
     */
    public static CorsPolicyBuilder builderForOriginRegex(String regex) {
        return builderForOriginRegex(Pattern.compile(requireNonNull(regex, "regex")));
    }

    /**
     * Returns a new {@link CorsPolicyBuilder} with origins matching the {@code regex}.
     */
    public static CorsPolicyBuilder builderForOriginRegex(Pattern regex) {
        return builder(requireNonNull(regex, "regex").asPredicate());
    }

    private final Set<String> origins;
    private final Predicate<String> originPredicate;
    private final List<Route> routes;
    private final boolean credentialsAllowed;
    private final boolean nullOriginAllowed;
    private final long maxAge;
    private final Set<AsciiString> exposedHeaders;
    private final Set<HttpMethod> allowedRequestMethods;
    private final boolean allowAllRequestHeaders;
    private final Set<AsciiString> allowedRequestHeaders;

    private final String joinedAllowedRequestMethods;
    private final Map<AsciiString, Supplier<?>> preflightResponseHeaders;

    CorsPolicy(Set<String> origins, Predicate<? super String> originPredicate,
               List<Route> routes, boolean credentialsAllowed, long maxAge,
               boolean nullOriginAllowed, Set<AsciiString> exposedHeaders,
               boolean allowAllRequestHeaders, Set<AsciiString> allowedRequestHeaders,
               EnumSet<HttpMethod> allowedRequestMethods, boolean preflightResponseHeadersDisabled,
               Map<AsciiString, Supplier<?>> preflightResponseHeaders) {
        this.origins = ImmutableSet.copyOf(origins);
        this.originPredicate = (Predicate<String>) originPredicate;
        this.routes = ImmutableList.copyOf(routes);
        this.credentialsAllowed = credentialsAllowed;
        this.maxAge = maxAge;
        this.nullOriginAllowed = nullOriginAllowed;
        this.exposedHeaders = ImmutableSet.copyOf(exposedHeaders);
        this.allowedRequestMethods = ImmutableSet.copyOf(allowedRequestMethods);
        this.allowAllRequestHeaders = allowAllRequestHeaders;
        this.allowedRequestHeaders = ImmutableSet.copyOf(allowedRequestHeaders);
        joinedAllowedRequestMethods = this.allowedRequestMethods
                .stream().map(HttpMethod::name).collect(Collectors.joining(DELIMITER));
        if (preflightResponseHeadersDisabled) {
            this.preflightResponseHeaders = Collections.emptyMap();
        } else if (preflightResponseHeaders.isEmpty()) {
            this.preflightResponseHeaders = ImmutableMap.of(
                    HttpHeaderNames.DATE, HttpTimestampSupplier::currentTime,
                    HttpHeaderNames.CONTENT_LENGTH, ConstantValueSupplier.ZERO);
        } else {
            this.preflightResponseHeaders = ImmutableMap.copyOf(preflightResponseHeaders);
        }
    }

    /**
     * Returns the allowed origin. This can either be a wildcard or an origin value.
     * This method returns the first specified origin if this policy has more than one origin.
     *
     * @return the value that will be used for the CORS response header {@code "Access-Control-Allow-Origin"}
     *
     * @deprecated Use {@link #originPredicate()} to check if an origin is allowed.
     */
    @Deprecated
    public String origin() {
        return Iterables.getFirst(origins, CorsHeaderUtil.ANY_ORIGIN);
    }

    /**
     * Returns the set of allowed origins.
     *
     * @deprecated @deprecated Use {@link #originPredicate()} to check if an origin is allowed.
     */
    @Deprecated
    public Set<String> origins() {
        return origins;
    }

    /**
     * Returns predicate to match origins.
     */
    public Predicate<String> originPredicate() {
        return originPredicate;
    }

    /**
     * Returns the list of {@link Route}s that this policy is supposed to be applied to.
     */
    public List<Route> routes() {
        return routes;
    }

    /**
     * Determines if cookies are supported for CORS requests.
     *
     * <p>By default cookies are not included in CORS requests but if {@code isCredentialsAllowed} returns
     * {@code true} cookies will be added to CORS requests. Setting this value to {@code true} will set the
     * CORS {@code "Access-Control-Allow-Credentials"} response header to {@code true}.
     *
     * <p>Please note that cookie support needs to be enabled on the client side as well.
     * The client needs to opt-in to send cookies by calling:
     * <pre>{@code
     * xhr.withCredentials = true;
     * }</pre>
     *
     * <p>The default value for {@code 'withCredentials'} is {@code false} in which case no cookies are sent.
     * Setting {@code this} to {@code true} will include cookies in cross origin requests.
     *
     * @return {@code true} if cookies are supported.
     */
    public boolean isCredentialsAllowed() {
        return credentialsAllowed;
    }

    /**
     * Gets the {@code maxAge} setting.
     *
     * <p>When making a preflight request the client has to perform two requests which can be inefficient.
     * This setting will set the CORS {@code "Access-Control-Max-Age"} response header and enable the
     * caching of the preflight response for the specified time. During this time no preflight
     * request will be made.
     *
     * @return the time in seconds that a preflight request may be cached.
     */
    public long maxAge() {
        return maxAge;
    }

    /**
     * Returns a set of headers to be exposed to calling clients.
     *
     * <p>During a simple CORS request only certain response headers are made available by the
     * browser, for example using:
     * <pre>{@code
     * xhr.getResponseHeader("Content-Type");
     * }</pre>
     * The headers that are available by default are:
     * <ul>
     *   <li>{@code Cache-Control}</li>
     *   <li>{@code Content-Language}</li>
     *   <li>{@code Content-Type}</li>
     *   <li>{@code Expires}</li>
     *   <li>{@code Last-Modified}</li>
     *   <li>{@code Pragma}</li>
     * </ul>
     *
     * <p>To expose other headers they need to be specified, which is what this method enables by
     * adding the headers names to the CORS {@code "Access-Control-Expose-Headers"} response header.
     *
     * @return the list of the headers to expose.
     */
    public Set<AsciiString> exposedHeaders() {
        return exposedHeaders;
    }

    /**
     * Returns the allowed set of request methods. The Http methods that should be returned in the
     * CORS {@code "Access-Control-Request-Method"} response header.
     *
     * @return the {@link HttpMethod}s that represent the allowed request methods.
     */
    public Set<HttpMethod> allowedRequestMethods() {
        return allowedRequestMethods;
    }

    /**
     * Returns the allowed set of request headers.
     *
     * <p>The header names returned from this method will be used to set the CORS
     * {@code "Access-Control-Allow-Headers"} response header.
     */
    public Set<AsciiString> allowedRequestHeaders() {
        return allowedRequestHeaders;
    }

    /**
     * Determines if the policy allows a {@code "null"} origin.
     */
    public boolean isNullOriginAllowed() {
        return nullOriginAllowed;
    }

    /**
     * Generates immutable HTTP response headers that should be added to a CORS preflight response.
     *
     * @return {@link HttpHeaders} the HTTP response headers to be added.
     */
    public HttpHeaders generatePreflightResponseHeaders() {
        final HttpHeadersBuilder headers = HttpHeaders.builder();
        preflightResponseHeaders.forEach((key, value) -> {
            final Object val = getValue(value);
            if (val instanceof Iterable) {
                headers.addObject(key, (Iterable<?>) val);
            } else {
                headers.addObject(key, val);
            }
        });
        return headers.build();
    }

    /**
     * This is a non CORS specification feature which enables the setting of preflight
     * response headers that might be required by intermediaries.
     *
     * @param headers the {@link ResponseHeadersBuilder} to which the preflight headers should be added.
     */
    void setCorsPreflightResponseHeaders(ResponseHeadersBuilder headers) {
        headers.add(generatePreflightResponseHeaders());
    }

    /**
     * Determines whether all request headers are allowed.
     *
     * @return true if all request headers are allowed,
     *         false if only specific request headers are allowed.
     */
    public boolean shouldAllowAllRequestHeaders() {
        return allowAllRequestHeaders;
    }

    void setCorsAllowMethods(ResponseHeadersBuilder headers) {
        headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, joinedAllowedRequestMethods);
    }

    void setCorsMaxAge(ResponseHeadersBuilder headers) {
        headers.setLong(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, maxAge);
    }

    @Override
    public String toString() {
        return toString(this, origins, routes, nullOriginAllowed, credentialsAllowed, maxAge,
                        exposedHeaders, allowedRequestMethods, allowAllRequestHeaders, allowedRequestHeaders,
                        preflightResponseHeaders);
    }

    static String toString(Object obj, @Nullable Set<String> origins, List<Route> routes,
                           boolean nullOriginAllowed, boolean credentialsAllowed,
                           long maxAge, @Nullable Set<AsciiString> exposedHeaders,
                           @Nullable Set<HttpMethod> allowedRequestMethods,
                           boolean allowAllRequestHeaders,
                           @Nullable Set<AsciiString> allowedRequestHeaders,
                           @Nullable Map<AsciiString, Supplier<?>> preflightResponseHeaders) {
        return MoreObjects.toStringHelper(obj)
                          .omitNullValues()
                          .add("origins", origins)
                          .add("routes", routes)
                          .add("nullOriginAllowed", nullOriginAllowed)
                          .add("credentialsAllowed", credentialsAllowed)
                          .add("maxAge", maxAge)
                          .add("exposedHeaders", exposedHeaders)
                          .add("allowedRequestMethods", allowedRequestMethods)
                          .add("allowAllRequestHeaders", allowAllRequestHeaders)
                          .add("allowedRequestHeaders", allowedRequestHeaders)
                          .add("preflightResponseHeaders", preflightResponseHeaders).toString();
    }

    private static <T> T getValue(Supplier<T> callable) {
        try {
            return callable.get();
        } catch (final Exception e) {
            throw new IllegalStateException("could not generate value for supplier: " + callable, e);
        }
    }
}
