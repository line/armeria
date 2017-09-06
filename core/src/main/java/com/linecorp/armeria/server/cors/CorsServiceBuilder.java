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

import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.common.base.Ascii;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.cors.CorsConfig.ConstantValueSupplier;

import io.netty.util.AsciiString;

/**
 * Builds a new {@link CorsService} or its decorator function.
 */
public final class CorsServiceBuilder {

    /**
     * Creates a new builder with its origin set to '*'.
     */
    public static CorsServiceBuilder forAnyOrigin() {
        return new CorsServiceBuilder();
    }

    /**
     * Creates a new builder with the specified origin.
     */
    public static CorsServiceBuilder forOrigin(final String origin) {
        requireNonNull(origin, "origin");

        if ("*".equals(origin)) {
            return new CorsServiceBuilder();
        }
        return new CorsServiceBuilder(origin);
    }

    /**
     * Creates a new builder with the specified origins.
     */
    public static CorsServiceBuilder forOrigins(final String... origins) {
        requireNonNull(origins, "origins");
        for (int i = 0; i < origins.length; i++) {
            if (origins[i] == null) {
                throw new NullPointerException("origins[" + i + ']');
            }
        }
        return new CorsServiceBuilder(origins);
    }

    final Set<String> origins;
    final boolean anyOriginSupported;
    boolean nullOriginAllowed;
    boolean credentialsAllowed;
    boolean shortCircuit;
    long maxAge;
    final Set<AsciiString> exposedHeaders = new HashSet<>();
    @SuppressWarnings("SetReplaceableByEnumSet")
    final Set<HttpMethod> allowedRequestMethods = new HashSet<>();
    final Set<AsciiString> allowedRequestHeaders = new HashSet<>();
    final Map<AsciiString, Supplier<?>> preflightResponseHeaders = new HashMap<>();
    boolean preflightResponseHeadersDisabled;

    /**
     * Creates a new instance.
     *
     * @param origins the origin to be used for this builder.
     */
    CorsServiceBuilder(final String... origins) {
        final Set<String> originsCopy = new LinkedHashSet<>();
        for (String o : origins) {
            originsCopy.add(Ascii.toLowerCase(o));
        }
        this.origins = Collections.unmodifiableSet(originsCopy);
        anyOriginSupported = false;
    }

    /**
     * Creates a new Builder instance allowing any origin, "*" which is the
     * wildcard origin.
     */
    CorsServiceBuilder() {
        anyOriginSupported = true;
        origins = Collections.emptySet();
    }

    /**
     * Web browsers may set the 'Origin' request header to 'null' if a resource is loaded
     * from the local file system. Calling this method will enable a successful CORS response
     * with a {@code "null"} value for the the CORS response header 'Access-Control-Allow-Origin'.
     *
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder allowNullOrigin() {
        nullOriginAllowed = true;
        return this;
    }

    /**
     * By default cookies are not included in CORS requests, but this method will enable cookies to
     * be added to CORS requests. Calling this method will set the CORS 'Access-Control-Allow-Credentials'
     * response header to true.
     *
     * <p>Please note, that cookie support needs to be enabled on the client side as well.
     * The client needs to opt-in to send cookies by calling:
     * <pre>{@code
     * xhr.withCredentials = true;
     * }</pre>
     *
     * <p>The default value for 'withCredentials' is false in which case no cookies are sent.
     * Setting this to true will included cookies in cross origin requests.
     *
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder allowCredentials() {
        credentialsAllowed = true;
        return this;
    }

    /**
     * Specifies that a CORS request should be rejected if it's invalid before being
     * further processing.
     *
     * <p>CORS headers are set after a request is processed. This may not always be desired
     * and this setting will check that the Origin is valid and if it is not valid no
     * further processing will take place, and a error will be returned to the calling client.
     *
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder shortCircuit() {
        shortCircuit = true;
        return this;
    }

    /**
     * When making a preflight request the client has to perform two request with can be inefficient.
     * This setting will set the CORS 'Access-Control-Max-Age' response header and enables the
     * caching of the preflight response for the specified time. During this time no preflight
     * request will be made.
     *
     * @param maxAge the maximum time, in seconds, that the preflight response may be cached.
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder maxAge(final long maxAge) {
        if (maxAge <= 0) {
            throw new IllegalArgumentException("maxAge: " + maxAge + " (expected: > 0)");
        }
        this.maxAge = maxAge;
        return this;
    }

    /**
     * Specifies the headers to be exposed to calling clients.
     *
     * <p>During a simple CORS request, only certain response headers are made available by the
     * browser, for example using:
     * <pre>{@code
     * xhr.getResponseHeader("Content-Type");
     * }</pre>
     *
     * <p>The headers that are available by default are:
     * <ul>
     * <li>Cache-Control</li>
     * <li>Content-Language</li>
     * <li>Content-Type</li>
     * <li>Expires</li>
     * <li>Last-Modified</li>
     * <li>Pragma</li>
     * </ul>
     *
     * <p>To expose other headers they need to be specified which is what this method enables by
     * adding the headers to the CORS 'Access-Control-Expose-Headers' response header.
     *
     * @param headers the values to be added to the 'Access-Control-Expose-Headers' response header
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder exposeHeaders(final String... headers) {
        requireNonNull(headers, "headers");
        for (int i = 0; i < headers.length; i++) {
            if (headers[i] == null) {
                throw new NullPointerException("headers[" + i + ']');
            }
        }

        Arrays.stream(headers).map(HttpHeaderNames::of).forEach(exposedHeaders::add);
        return this;
    }

    /**
     * Specifies the headers to be exposed to calling clients.
     *
     * <p>During a simple CORS request, only certain response headers are made available by the
     * browser, for example using:
     * <pre>{@code
     * xhr.getResponseHeader("Content-Type");
     * }</pre>
     *
     * <p>The headers that are available by default are:
     * <ul>
     * <li>Cache-Control</li>
     * <li>Content-Language</li>
     * <li>Content-Type</li>
     * <li>Expires</li>
     * <li>Last-Modified</li>
     * <li>Pragma</li>
     * </ul>
     *
     * <p>To expose other headers they need to be specified which is what this method enables by
     * adding the headers to the CORS 'Access-Control-Expose-Headers' response header.
     *
     * @param headers the values to be added to the 'Access-Control-Expose-Headers' response header
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder exposeHeaders(final AsciiString... headers) {
        requireNonNull(headers, "headers");
        for (int i = 0; i < headers.length; i++) {
            if (headers[i] == null) {
                throw new NullPointerException("headers[" + i + ']');
            }
        }
        Collections.addAll(exposedHeaders, headers);
        return this;
    }

    /**
     * Specifies the allowed set of HTTP Request Methods that should be returned in the
     * CORS 'Access-Control-Request-Method' response header.
     *
     * @param methods the {@link HttpMethod}s that should be allowed.
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder allowRequestMethods(final HttpMethod... methods) {
        requireNonNull(methods, "methods");
        for (int i = 0; i < methods.length; i++) {
            if (methods[i] == null) {
                throw new NullPointerException("methods[" + i + ']');
            }
        }
        Collections.addAll(allowedRequestMethods, methods);
        return this;
    }

    /**
     * Specifies the if headers that should be returned in the CORS 'Access-Control-Allow-Headers'
     * response header.
     *
     * <p>If a client specifies headers on the request, for example by calling:
     * <pre>{@code
     * xhr.setRequestHeader('My-Custom-Header', "SomeValue");
     * }</pre>
     * the server will receive the above header name in the 'Access-Control-Request-Headers' of the
     * preflight request. The server will then decide if it allows this header to be sent for the
     * real request (remember that a preflight is not the real request but a request asking the server
     * if it allow a request).
     *
     * @param headers the headers to be added to the preflight 'Access-Control-Allow-Headers' response header.
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder allowRequestHeaders(final String... headers) {
        requireNonNull(headers, "headers");
        for (int i = 0; i < headers.length; i++) {
            if (headers[i] == null) {
                throw new NullPointerException("headers[" + i + ']');
            }
        }

        Arrays.stream(headers).map(HttpHeaderNames::of).forEach(allowedRequestHeaders::add);
        return this;
    }

    /**
     * Specifies the if headers that should be returned in the CORS 'Access-Control-Allow-Headers'
     * response header.
     *
     * <p>If a client specifies headers on the request, for example by calling:
     * <pre>{@code
     * xhr.setRequestHeader('My-Custom-Header', "SomeValue");
     * }</pre>
     * the server will receive the above header name in the 'Access-Control-Request-Headers' of the
     * preflight request. The server will then decide if it allows this header to be sent for the
     * real request (remember that a preflight is not the real request but a request asking the server
     * if it allow a request).
     *
     * @param headers the headers to be added to the preflight 'Access-Control-Allow-Headers' response header.
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder allowRequestHeaders(final AsciiString... headers) {
        requireNonNull(headers, "headers");
        for (int i = 0; i < headers.length; i++) {
            if (headers[i] == null) {
                throw new NullPointerException("headers[" + i + ']');
            }
        }
        allowedRequestHeaders.addAll(Arrays.asList(headers));
        return this;
    }

    /**
     * Returns HTTP response headers that should be added to a CORS preflight response.
     *
     * <p>An intermediary like a load balancer might require that a CORS preflight request
     * have certain headers set. This enables such headers to be added.
     *
     * @param name the name of the HTTP header.
     * @param values the values for the HTTP header.
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder preflightResponseHeader(final String name, final Object... values) {
        requireNonNull(name, "name");
        return preflightResponseHeader(HttpHeaderNames.of(name), values);
    }

    /**
     * Returns HTTP response headers that should be added to a CORS preflight response.
     *
     * <p>An intermediary like a load balancer might require that a CORS preflight request
     * have certain headers set. This enables such headers to be added.
     *
     * @param name the name of the HTTP header.
     * @param values the values for the HTTP header.
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder preflightResponseHeader(final AsciiString name, final Object... values) {
        requireNonNull(name, "name");
        requireNonNull(values, "values");
        for (int i = 0;i < values.length; i++) {
            if (values[i] == null) {
                throw new NullPointerException("values[" + i + ']');
            }
        }

        if (values.length == 1) {
            preflightResponseHeaders.put(name, new ConstantValueSupplier(values[0]));
        } else {
            preflightResponseHeader(name, Arrays.asList(values));
        }
        return this;
    }

    /**
     * Returns HTTP response headers that should be added to a CORS preflight response.
     *
     * <p>An intermediary like a load balancer might require that a CORS preflight request
     * have certain headers set. This enables such headers to be added.
     *
     * @param name the name of the HTTP header.
     * @param values the values for the HTTP header.
     * @param <T> the type of values that the Iterable contains.
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public <T> CorsServiceBuilder preflightResponseHeader(final AsciiString name, final Iterable<T> values) {
        requireNonNull(name, "name");
        requireNonNull(values, "values");
        preflightResponseHeaders.put(name, new ConstantValueSupplier(values));
        return this;
    }

    /**
     * Returns HTTP response headers that should be added to a CORS preflight response.
     *
     * <p>An intermediary like a load balancer might require that a CORS preflight request
     * have certain headers set. This enables such headers to be added.
     *
     * <p>Some values must be dynamically created when the HTTP response is created, for
     * example the 'Date' response header. This can be accomplished by using a {@link Supplier}
     * which will have its 'call' method invoked when the HTTP response is created.
     *
     * @param name the name of the HTTP header.
     * @param valueSupplier a {@link Supplier} which will be invoked at HTTP response creation.
     * @param <T> the type of the value that the {@link Supplier} can return.
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public <T> CorsServiceBuilder preflightResponseHeader(AsciiString name, Supplier<T> valueSupplier) {
        requireNonNull(name, "name");
        requireNonNull(valueSupplier, "valueSupplier");
        preflightResponseHeaders.put(name, valueSupplier);
        return this;
    }

    /**
     * Specifies that no preflight response headers should be added to a preflight response.
     *
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder disablePreflightResponseHeaders() {
        preflightResponseHeadersDisabled = true;
        return this;
    }

    /**
     * Returns a newly-created {@link CorsService} based on the properties of this builder.
     */
    public CorsService build(Service<HttpRequest, HttpResponse> delegate) {
        return new CorsService(delegate, new CorsConfig(this));
    }

    /**
     * Returns a newly-created decorator that decorates a {@link Service} with a new {@link CorsService}
     * based on the properties of this builder.
     */
    public Function<Service<HttpRequest, HttpResponse>, CorsService> newDecorator() {
        final CorsConfig config = new CorsConfig(this);
        return s -> new CorsService(s, config);
    }

    @Override
    public String toString() {
        return CorsConfig.toString(this, true, origins, anyOriginSupported, nullOriginAllowed,
                                   credentialsAllowed, shortCircuit, maxAge, exposedHeaders,
                                   allowedRequestMethods, allowedRequestHeaders, preflightResponseHeaders);
    }
}
