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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.google.common.base.Ascii;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.server.cors.CorsConfig.ConstantValueSupplier;

import io.netty.util.AsciiString;

/**
 * Contains information for the build of the CORS policy.
 *
 * @see ChainedCorsPolicyBuilder
 * @see CorsPolicyBuilder
 */
@SuppressWarnings("rawtypes")
abstract class AbstractCorsPolicyBuilder<B extends AbstractCorsPolicyBuilder> {
    private final Set<String> origins;
    private boolean credentialsAllowed;
    private boolean nullOriginAllowed;
    private long maxAge;
    private final Set<AsciiString> exposedHeaders = new HashSet<>();
    private final Set<HttpMethod> allowedRequestMethods = new HashSet<>();
    private final Set<AsciiString> allowedRequestHeaders = new HashSet<>();
    private final Map<AsciiString, Supplier<?>> preflightResponseHeaders = new HashMap<>();
    private boolean preflightResponseHeadersDisabled;

    AbstractCorsPolicyBuilder() {
        origins = Collections.emptySet();
    }

    AbstractCorsPolicyBuilder(String... origins) {
        requireNonNull(origins, "origins");
        for (int i = 0; i < origins.length; i++) {
            if (origins[i] == null) {
                throw new NullPointerException("origins[" + i + ']');
            }
        }
        this.origins = Arrays.stream(origins).map(Ascii::toLowerCase).collect(toImmutableSet());
    }

    @SuppressWarnings("unchecked")
    B self() {
        return (B) this;
    }

    public Set<String> origins() {
        return origins;
    }

    /**
     * Determines if the policy allows a null origin.
     */
    boolean isNullOriginAllowed() {
        return nullOriginAllowed;
    }

    /**
     * Enables a successful CORS response with a {@code null} value for the CORS response header
     * {@code 'Access-Control-Allow-Origin'}. Web browsers may set the {@code 'Origin'} request header to
     * {@code "null"} if a resource is loaded from the local file system.
     *
     * @return {@code this} to support method chaining.
     */
    public B allowNullOrigin() {
        nullOriginAllowed = true;
        return self();
    }

    /**
     * Enables cookies to be added to CORS requests.
     * Calling this method will set the CORS {@code 'Access-Control-Allow-Credentials'} response header to true.
     * By default cookies are not included in CORS requests
     *
     * <p>Please note, that cookie support needs to be enabled on the client side as well.
     * The client needs to opt-in to send cookies by calling:
     * <pre>{@code
     * xhr.withCredentials = true;
     * }</pre>
     *
     * <p>The default value for {@code 'withCredentials'} is false in which case no cookies are sent.
     * Setting this to true will included cookies in cross origin requests.
     *
     * @return {@code this} to support method chaining.
     */
    public B allowCredentials() {
        credentialsAllowed = true;
        return self();
    }

    /**
     * Sets the CORS {@code 'Access-Control-Max-Age'} response header and enables the
     * caching of the preflight response for the specified time. During this time no preflight
     * request will be made.
     *
     * @param maxAge the maximum time, in seconds, that the preflight response may be cached.
     * @return {@code this} to support method chaining.
     */
    public B maxAge(final long maxAge) {
        checkState(maxAge <= 0, "maxAge: %d (expected: > 0)", maxAge);
        this.maxAge = maxAge;
        return self();
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
     *    <li>Cache-Control</li>
     *    <li>Content-Language</li>
     *    <li>Content-Type</li>
     *    <li>Expires</li>
     *    <li>Last-Modified</li>
     *    <li>Pragma</li>
     * </ul>
     *
     * <p>To expose other headers they need to be specified which is what this method enables by
     * adding the headers to the CORS {@code 'Access-Control-Expose-Headers'} response header.
     *
     * @param headers the values to be added to the {@code 'Access-Control-Expose-Headers'} response header
     * @return {@code this} to support method chaining.
     */
    public B exposeHeaders(final CharSequence... headers) {
        requireNonNull(headers, "headers");
        for (int i = 0; i < headers.length; i++) {
            if (headers[i] == null) {
                throw new NullPointerException("headers[" + i + ']');
            }
        }

        Arrays.stream(headers).map(HttpHeaderNames::of).forEach(exposedHeaders::add);
        return self();
    }

    /**
     * Specifies the allowed set of HTTP Request Methods that should be returned in the
     * CORS {@code 'Access-Control-Request-Method'} response header.
     *
     * @param methods the {@link HttpMethod}s that should be allowed.
     * @return {@code this} to support method chaining.
     */
    public B allowRequestMethods(final HttpMethod... methods) {
        requireNonNull(methods, "methods");
        for (int i = 0; i < methods.length; i++) {
            if (methods[i] == null) {
                throw new NullPointerException("methods[" + i + ']');
            }
        }
        Collections.addAll(allowedRequestMethods, methods);
        return self();
    }

    /**
     * Specifies the if headers that should be returned in the CORS {@code 'Access-Control-Allow-Headers'}
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
     * @param headers the headers to be added to
     *                the preflight {@code 'Access-Control-Allow-Headers'} response header.
     * @return {@code this} to support method chaining.
     */
    public B allowRequestHeaders(final CharSequence... headers) {
        requireNonNull(headers, "headers");
        final List<AsciiString> asciiHeaders = new ArrayList<>();
        for (int i = 0; i < headers.length; i++) {
            if (headers[i] == null) {
                throw new NullPointerException("headers[" + i + ']');
            }
            asciiHeaders.add(AsciiString.of(headers[i]));
        }
        allowedRequestHeaders.addAll(asciiHeaders);
        return self();
    }

    /**
     * Returns HTTP response headers that should be added to a CORS preflight response.
     *
     * <p>An intermediary like a load balancer might require that a CORS preflight request
     * have certain headers set. This enables such headers to be added.
     *
     * @param name the name of the HTTP header.
     * @param values the values for the HTTP header.
     * @return {@code this} to support method chaining.
     */
    public B preflightResponseHeader(final CharSequence name, final Object... values) {
        requireNonNull(name, "name");
        requireNonNull(values, "values");
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                throw new NullPointerException("values[" + i + ']');
            }
        }
        preflightResponseHeaders.put(AsciiString.of(name), new ConstantValueSupplier(Arrays.asList(values)));
        return self();
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
     * @return {@code this} to support method chaining.
     */
    public <T> B preflightResponseHeader(final CharSequence name, final Iterable<T> values) {
        requireNonNull(name, "name");
        requireNonNull(values, "values");
        preflightResponseHeaders.put(AsciiString.of(name), new ConstantValueSupplier(values));
        return self();
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
     * @return {@code this} to support method chaining.
     */
    public <T> B preflightResponseHeader(CharSequence name, Supplier<T> valueSupplier) {
        requireNonNull(name, "name");
        requireNonNull(valueSupplier, "valueSupplier");
        preflightResponseHeaders.put(AsciiString.of(name), valueSupplier);
        return self();
    }

    /**
     * Specifies that no preflight response headers should be added to a preflight response.
     *
     * @return {@code this} to support method chaining.
     */
    public B disablePreflightResponseHeaders() {
        preflightResponseHeadersDisabled = true;
        return self();
    }

    /**
     * Returns a newly-created {@link CorsPolicy} based on the properties of this builder.
     */
    public CorsPolicy build() {
        return new CorsPolicy(origins, credentialsAllowed, maxAge, nullOriginAllowed,
                              exposedHeaders, allowedRequestHeaders, allowedRequestMethods,
                              preflightResponseHeadersDisabled, preflightResponseHeaders);
    }

    @Override
    public String toString() {
        return CorsPolicy.toString(this, origins, nullOriginAllowed, credentialsAllowed, maxAge,
                                   exposedHeaders, allowedRequestMethods, allowedRequestHeaders,
                                   preflightResponseHeaders);
    }
}
