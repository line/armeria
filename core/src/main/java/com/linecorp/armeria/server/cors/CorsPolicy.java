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

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.DefaultHttpHeaders;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.server.cors.CorsConfig.ConstantValueSupplier;
import com.linecorp.armeria.server.cors.CorsConfig.InstantValueSupplier;

import io.netty.util.AsciiString;

public final class CorsPolicy {
    private static final String ANY_ORIGIN = "*";
    private static final String NULL_ORIGIN = "null";
    private static final String DELIMITER = ",";
    private static final Joiner HEADER_JOINER = Joiner.on(DELIMITER);
    private final Set<String> origins;
    private final boolean credentialsAllowed;
    private final boolean nullOriginAllowed;
    private final boolean shortCircuit;
    private final long maxAge;
    private final Set<AsciiString> exposedHeaders;
    private final Set<HttpMethod> allowedRequestMethods;
    private final Set<AsciiString> allowedRequestHeaders;
    private final Map<AsciiString, Supplier<?>> preflightResponseHeaders;

    CorsPolicy(final CorsPolicyBuilder builder) {
        origins = builder.origins;
        credentialsAllowed = builder.credentialsAllowed;
        shortCircuit = builder.shortCircuit;
        maxAge = builder.maxAge;
        nullOriginAllowed = builder.nullOriginAllowed;
        exposedHeaders = ImmutableSet.copyOf(builder.exposedHeaders);
        allowedRequestMethods = EnumSet.copyOf(builder.allowedRequestMethods);
        allowedRequestHeaders = ImmutableSet.copyOf(builder.allowedRequestHeaders);

        final Map<AsciiString, Supplier<?>> preflightResponseHeaders;
        if (builder.preflightResponseHeadersDisabled) {
            preflightResponseHeaders = Collections.emptyMap();
        } else if (builder.preflightResponseHeaders.isEmpty()) {
            preflightResponseHeaders = ImmutableMap.of(
                    HttpHeaderNames.DATE, InstantValueSupplier.INSTANCE,
                    HttpHeaderNames.CONTENT_LENGTH, ConstantValueSupplier.ZERO);
        } else {
            preflightResponseHeaders = ImmutableMap.copyOf(builder.preflightResponseHeaders);
        }

        this.preflightResponseHeaders = preflightResponseHeaders;
    }

    /**
     * Returns the allowed origin. This can either be a wildcard or an origin value.
     *
     * @return the value that will be used for the CORS response header 'Access-Control-Allow-Origin'
     */
    public String origin() {
        return origins.isEmpty() ? "*" : origins.iterator().next();
    }

    /**
     * Returns the set of allowed origins.
     */
    public Set<String> origins() {
        return origins;
    }

    /**
     * Determines if cookies are supported for CORS requests.
     *
     * <p>By default cookies are not included in CORS requests but if isCredentialsAllowed returns
     * true cookies will be added to CORS requests. Setting this value to true will set the
     * CORS 'Access-Control-Allow-Credentials' response header to true.
     *
     * <p>Please note that cookie support needs to be enabled on the client side as well.
     * The client needs to opt-in to send cookies by calling:
     * <pre>{@code
     * xhr.withCredentials = true;
     * }</pre>
     *
     * <p>The default value for 'withCredentials' is false in which case no cookies are sent.
     * Setting this to true will included cookies in cross origin requests.
     *
     * @return {@code true} if cookies are supported.
     */
    public boolean isCredentialsAllowed() {
        return credentialsAllowed;
    }

    /**
     * Determines whether a CORS request should be rejected if it's invalid before being
     * further processing.
     *
     * <p>CORS headers are set after a request is processed. This may not always be desired
     * and this setting will check that the Origin is valid and if it is not valid no
     * further processing will take place, and a error will be returned to the calling client.
     *
     * @return {@code true} if a CORS request should short-circuit upon receiving an invalid Origin header.
     */
    public boolean isShortCircuit() {
        return shortCircuit;
    }

    /**
     * Gets the maxAge setting.
     *
     * <p>When making a preflight request the client has to perform two request with can be inefficient.
     * This setting will set the CORS 'Access-Control-Max-Age' response header and enables the
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
     * <li>Cache-Control</li>
     * <li>Content-Language</li>
     * <li>Content-Type</li>
     * <li>Expires</li>
     * <li>Last-Modified</li>
     * <li>Pragma</li>
     * </ul>
     *
     * <p>To expose other headers they need to be specified, which is what this method enables by
     * adding the headers names to the CORS 'Access-Control-Expose-Headers' response header.
     *
     * @return the list of the headers to expose.
     */
    public Set<AsciiString> exposedHeaders() {
        return exposedHeaders;
    }

    /**
     * Returns the allowed set of Request Methods. The Http methods that should be returned in the
     * CORS 'Access-Control-Request-Method' response header.
     *
     * @return the {@link HttpMethod}s that represent the allowed Request Methods.
     */
    public Set<HttpMethod> allowedRequestMethods() {
        return allowedRequestMethods;
    }

    /**
     * Returns the allowed set of Request Headers.
     *
     * <p>The header names returned from this method will be used to set the CORS
     * 'Access-Control-Allow-Headers' response header.
     */
    public Set<AsciiString> allowedRequestHeaders() {
        return allowedRequestHeaders;
    }

    /**
     * TODO: add javadoc.
     */
    public boolean isNullOriginAllowed() {
        return nullOriginAllowed;
    }

    /**
     * This is a non CORS specification feature which enables the setting of preflight
     * response headers that might be required by intermediaries.
     *
     * @param headers the {@link HttpHeaders} to which the preflight headers should be added.
     */
    void setPreflightHeaders(final HttpHeaders headers) {
        for (Map.Entry<AsciiString, String> entry : preflightResponseHeaders()) {
            headers.add(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Returns HTTP response headers that should be added to a CORS preflight response.
     *
     * @return {@link HttpHeaders} the HTTP response headers to be added.
     *
     * @throws IllegalStateException if CORS support is not enabled
     */
    public HttpHeaders preflightResponseHeaders() {
        if (preflightResponseHeaders.isEmpty()) {
            return HttpHeaders.EMPTY_HEADERS;
        }

        final HttpHeaders preflightHeaders = new DefaultHttpHeaders(false);
        for (Entry<AsciiString, Supplier<?>> entry : preflightResponseHeaders.entrySet()) {
            final Object value = getValue(entry.getValue());
            if (value instanceof Iterable) {
                preflightHeaders.addObject(entry.getKey(), (Iterable<?>) value);
            } else {
                preflightHeaders.addObject(entry.getKey(), value);
            }
        }
        return preflightHeaders;
    }

    private static <T> T getValue(final Supplier<T> callable) {
        try {
            return callable.get();
        } catch (final Exception e) {
            throw new IllegalStateException("could not generate value for supplier: " + callable, e);
        }
    }

    void setCorsAllowCredentials(final HttpHeaders headers) {
        if (credentialsAllowed &&
            !headers.get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN).equals(ANY_ORIGIN)) {
            headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        }
    }

    void setCorsExposeHeaders(final HttpHeaders headers) {
        if (exposedHeaders.isEmpty()) {
            return;
        }

        headers.set(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS, HEADER_JOINER.join(exposedHeaders));
    }

    void setCorsAllowMethods(final HttpHeaders headers) {
        final String methods = allowedRequestMethods
                .stream().map(HttpMethod::name).collect(Collectors.joining(DELIMITER));
        headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, methods);
    }

    void setCorsAllowHeaders(final HttpHeaders headers) {
        if (allowedRequestHeaders.isEmpty()) {
            return;
        }

        headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, HEADER_JOINER.join(allowedRequestHeaders));
    }

    void setCorsMaxAge(final HttpHeaders headers) {
        headers.setLong(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, maxAge);
    }

    @Override
    public String toString() {
        return toString(this, origins, nullOriginAllowed, credentialsAllowed, shortCircuit, maxAge,
                        exposedHeaders, allowedRequestMethods, allowedRequestHeaders, preflightResponseHeaders);
    }

    static String toString(Object obj, @Nullable Set<String> origins,
                           boolean nullOriginAllowed, boolean credentialsAllowed,
                           boolean shortCircuit, long maxAge, @Nullable Set<AsciiString> exposedHeaders,
                           @Nullable Set<HttpMethod> allowedRequestMethods,
                           @Nullable Set<AsciiString> allowedRequestHeaders,
                           @Nullable Map<AsciiString, Supplier<?>> preflightResponseHeaders) {
        return MoreObjects.toStringHelper(obj)
                          .add("origins", origins)
                          .add("nullOriginAllowed", nullOriginAllowed)
                          .add("credentialsAllowed", credentialsAllowed)
                          .add("shortCircuit", shortCircuit)
                          .add("maxAge", maxAge)
                          .add("exposedHeaders", exposedHeaders)
                          .add("allowedRequestMethods", allowedRequestMethods)
                          .add("allowedRequestHeaders", allowedRequestHeaders)
                          .add("preflightResponseHeaders", preflightResponseHeaders).toString();
    }
}
