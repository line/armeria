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

package com.linecorp.armeria.server.cors;

import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Supplier;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.DefaultHttpHeaders;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;

import io.netty.util.AsciiString;

/**
 * <a href="https://en.wikipedia.org/wiki/Cross-origin_resource_sharing">Cross-Origin Resource Sharing
 * (CORS)</a> configuration.
 *
 * @see CorsServiceBuilder
 * @see CorsService#config()
 */
public final class CorsConfig {

    /**
     * {@link CorsConfig} with CORS disabled.
     */
    public static final CorsConfig DISABLED = new CorsConfig();

    private final boolean enabled;
    private final Set<String> origins;
    private final boolean anyOriginSupported;
    private final boolean nullOriginAllowed;
    private final boolean credentialsAllowed;
    private final boolean shortCircuit;
    private final long maxAge;
    private final Set<AsciiString> exposedHeaders;
    private final Set<HttpMethod> allowedRequestMethods;
    private final Set<AsciiString> allowedRequestHeaders;
    private final Map<AsciiString, Supplier<?>> preflightResponseHeaders;

    CorsConfig() {
        enabled = false;
        origins = null;
        anyOriginSupported = false;
        nullOriginAllowed = false;
        credentialsAllowed = false;
        shortCircuit = false;
        maxAge = 0;
        exposedHeaders = null;
        allowedRequestMethods = null;
        allowedRequestHeaders = null;
        preflightResponseHeaders = null;
    }

    CorsConfig(final CorsServiceBuilder builder) {
        enabled = true;
        origins = builder.origins;
        anyOriginSupported = builder.anyOriginSupported;
        nullOriginAllowed = builder.nullOriginAllowed;
        credentialsAllowed = builder.credentialsAllowed;
        shortCircuit = builder.shortCircuit;
        maxAge = builder.maxAge;

        exposedHeaders = builder.exposedHeaders.isEmpty() ? Collections.emptySet()
                                                          : ImmutableSet.copyOf(builder.exposedHeaders);
        allowedRequestMethods = EnumSet.copyOf(builder.allowedRequestMethods);
        allowedRequestHeaders =
                builder.allowedRequestHeaders.isEmpty() ? Collections.emptySet()
                                                        : ImmutableSet.copyOf(builder.allowedRequestHeaders);

        final Map<AsciiString, Supplier<?>> preflightResponseHeaders;
        if (builder.preflightResponseHeadersDisabled) {
            preflightResponseHeaders = Collections.emptyMap();
        } else if (builder.preflightResponseHeaders.isEmpty()) {
            preflightResponseHeaders = ImmutableMap.of(HttpHeaderNames.DATE, DateValueSupplier.INSTANCE,
                                               HttpHeaderNames.CONTENT_LENGTH, ConstantValueSupplier.ZERO);
        } else {
            preflightResponseHeaders = ImmutableMap.copyOf(builder.preflightResponseHeaders);
        }

        this.preflightResponseHeaders = preflightResponseHeaders;
    }

    /**
     * Determines if support for CORS is enabled.
     *
     * @return {@code true} if support for CORS is enabled, false otherwise.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the allowed origin. This can either be a wildcard or an origin value.
     *
     * @return the value that will be used for the CORS response header 'Access-Control-Allow-Origin'
     *
     * @throws IllegalStateException if CORS support is not enabled
     */
    public String origin() {
        ensureEnabled();
        return origins.isEmpty() ? "*" : origins.iterator().next();
    }

    /**
     * Returns the set of allowed origins.
     *
     * @throws IllegalStateException if CORS support is not enabled
     */
    public Set<String> origins() {
        ensureEnabled();
        return origins;
    }

    /**
     * Determines whether a wildcard origin, '*', is supported.
     *
     * @return {@code true} if any origin is allowed.
     *
     * @throws IllegalStateException if CORS support is not enabled
     */
    public boolean isAnyOriginSupported() {
        ensureEnabled();
        return anyOriginSupported;
    }

    /**
     * Web browsers may set the 'Origin' request header to 'null' if a resource is loaded
     * from the local file system.
     *
     * <p>If this property is true, the server will response with the wildcard for the
     * the CORS response header 'Access-Control-Allow-Origin'.
     *
     * @return {@code true} if a 'null' origin should be supported.
     *
     * @throws IllegalStateException if CORS support is not enabled
     */
    public boolean isNullOriginAllowed() {
        ensureEnabled();
        return nullOriginAllowed;
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
     *
     * @throws IllegalStateException if CORS support is not enabled
     */
    public boolean isCredentialsAllowed() {
        ensureEnabled();
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
     *
     * @throws IllegalStateException if CORS support is not enabled
     */
    public boolean isShortCircuit() {
        ensureEnabled();
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
     *
     * @throws IllegalStateException if CORS support is not enabled
     */
    public long maxAge() {
        ensureEnabled();
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
     *
     * @throws IllegalStateException if CORS support is not enabled
     */
    public Set<AsciiString> exposedHeaders() {
        ensureEnabled();
        return exposedHeaders;
    }

    /**
     * Returns the allowed set of Request Methods. The Http methods that should be returned in the
     * CORS 'Access-Control-Request-Method' response header.
     *
     * @return the {@link HttpMethod}s that represent the allowed Request Methods.
     *
     * @throws IllegalStateException if CORS support is not enabled
     */
    public Set<HttpMethod> allowedRequestMethods() {
        ensureEnabled();
        return allowedRequestMethods;
    }

    /**
     * Returns the allowed set of Request Headers.
     *
     * <p>The header names returned from this method will be used to set the CORS
     * 'Access-Control-Allow-Headers' response header.
     *
     * @throws IllegalStateException if CORS support is not enabled
     */
    public Set<AsciiString> allowedRequestHeaders() {
        ensureEnabled();
        return allowedRequestHeaders;
    }

    /**
     * Returns HTTP response headers that should be added to a CORS preflight response.
     *
     * @return {@link HttpHeaders} the HTTP response headers to be added.
     *
     * @throws IllegalStateException if CORS support is not enabled
     */
    public HttpHeaders preflightResponseHeaders() {
        ensureEnabled();

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

    private void ensureEnabled() {
        if (!isEnabled()) {
            throw new IllegalStateException("CORS support not enabled");
        }
    }

    private static <T> T getValue(final Supplier<T> callable) {
        try {
            return callable.get();
        } catch (final Exception e) {
            throw new IllegalStateException("could not generate value for supplier: " + callable, e);
        }
    }

    @Override
    public String toString() {
        return toString(this, enabled, origins, anyOriginSupported, nullOriginAllowed, credentialsAllowed,
                        shortCircuit, maxAge, exposedHeaders, allowedRequestMethods, allowedRequestHeaders,
                        preflightResponseHeaders);
    }

    static String toString(Object obj, boolean enabled, Set<String> origins, boolean anyOriginSupported,
                           boolean nullOriginAllowed, boolean credentialsAllowed, boolean shortCircuit,
                           long maxAge, Set<AsciiString> exposedHeaders, Set<HttpMethod> allowedRequestMethods,
                           Set<AsciiString> allowedRequestHeaders,
                           Map<AsciiString, Supplier<?>> preflightResponseHeaders) {
        if (enabled) {
            return MoreObjects.toStringHelper(obj)
                              .add("origins", origins)
                              .add("anyOriginSupported", anyOriginSupported)
                              .add("nullOriginAllowed", nullOriginAllowed)
                              .add("credentialsAllowed", credentialsAllowed)
                              .add("shortCircuit", shortCircuit)
                              .add("maxAge", maxAge)
                              .add("exposedHeaders", exposedHeaders)
                              .add("allowedRequestMethods", allowedRequestMethods)
                              .add("allowedRequestHeaders", allowedRequestHeaders)
                              .add("preflightResponseHeaders", preflightResponseHeaders).toString();
        } else {
            return obj.getClass().getSimpleName() + "{disabled}";
        }
    }

    /**
     * This class is used for preflight HTTP response values that do not need to be
     * generated, but instead the value is "static" in that the same value will be returned
     * for each call.
     */
    static final class ConstantValueSupplier implements Supplier<Object> {

        static final ConstantValueSupplier ZERO = new ConstantValueSupplier("0");

        private final Object value;

        ConstantValueSupplier(final Object value) {
            this.value = value;
        }

        @Override
        public Object get() {
            return value;
        }

        @Override
        public String toString() {
            return String.valueOf(value);
        }
    }

    /**
     * This {@link Supplier} is used for the DATE preflight HTTP response HTTP header.
     * It's value must be generated when the response is generated, hence will be
     * different for every call.
     */
    static final class DateValueSupplier implements Supplier<Date> {

        static final DateValueSupplier INSTANCE = new DateValueSupplier();

        @Override
        public Date get() {
            return new Date();
        }

        @Override
        public String toString() {
            return "<date>";
        }
    }
}
