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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.annotation.AdditionalHeader;
import com.linecorp.armeria.server.annotation.decorator.CorsDecorator;
import com.linecorp.armeria.server.cors.CorsConfig.ConstantValueSupplier;

import io.netty.util.AsciiString;

/**
 * Contains information for the build of the {@link CorsPolicy}.
 *
 * @see ChainedCorsPolicyBuilder
 * @see CorsPolicyBuilder
 */
abstract class AbstractCorsPolicyBuilder<SELF extends AbstractCorsPolicyBuilder<SELF>> {

    private final Set<String> origins;
    private final Predicate<? super String> originPredicate;
    private final List<Route> routes = new ArrayList<>();
    private boolean credentialsAllowed;
    private boolean nullOriginAllowed;
    private long maxAge;
    private final Set<AsciiString> exposedHeaders = new HashSet<>();
    private final EnumSet<HttpMethod> allowedRequestMethods = EnumSet.noneOf(HttpMethod.class);
    // FIXME(ghkim3221): Change the default value to true in Armeria 2.0.
    private boolean allowAllRequestHeaders;
    private final Set<AsciiString> allowedRequestHeaders = new HashSet<>();
    private final Map<AsciiString, Supplier<?>> preflightResponseHeaders = new HashMap<>();
    private boolean preflightResponseHeadersDisabled;

    AbstractCorsPolicyBuilder(List<String> origins) {
        requireNonNull(origins, "origins");
        checkArgument(!origins.isEmpty(), "origins is empty.");
        for (int i = 0; i < origins.size(); i++) {
            if (origins.get(i) == null) {
                throw new NullPointerException("origins[" + i + ']');
            }
        }
        this.origins = origins.stream().map(Ascii::toLowerCase).collect(toImmutableSet());
        originPredicate = this.origins::contains;
    }

    AbstractCorsPolicyBuilder(Predicate<? super String> originPredicate) {
        requireNonNull(originPredicate, "originPredicate");
        origins = ImmutableSet.of();
        this.originPredicate = originPredicate;
    }

    @SuppressWarnings("unchecked")
    final SELF self() {
        return (SELF) this;
    }

    final void setConfig(CorsDecorator corsDecorator) {
        Arrays.stream(corsDecorator.pathPatterns()).forEach(this::route);

        if (corsDecorator.credentialsAllowed()) {
            allowCredentials();
        }
        if (corsDecorator.nullOriginAllowed()) {
            allowNullOrigin();
        }
        if (corsDecorator.preflightRequestDisabled()) {
            disablePreflightResponseHeaders();
        }
        if (corsDecorator.exposedHeaders().length > 0) {
            exposeHeaders(corsDecorator.exposedHeaders());
        }
        if (corsDecorator.allowedRequestHeaders().length > 0) {
            allowRequestHeaders(corsDecorator.allowedRequestHeaders());
        }
        allowAllRequestHeaders(corsDecorator.allowAllRequestHeaders());
        if (corsDecorator.allowedRequestMethods().length > 0) {
            allowRequestMethods(corsDecorator.allowedRequestMethods());
        }
        for (AdditionalHeader additionalHeader : corsDecorator.preflightRequestHeaders()) {
            preflightResponseHeader(additionalHeader.name(), additionalHeader.value());
        }
        if (corsDecorator.maxAge() > 0) {
            maxAge(corsDecorator.maxAge());
        }
    }

    /**
     * Adds a path pattern that this policy is supposed to be applied to.
     *
     * @param pathPattern the path pattern that this policy is supposed to be applied to
     * @return {@code this} to support method chaining.
     * @throws IllegalArgumentException if the path pattern is not valid
     */
    public SELF route(String pathPattern) {
        requireNonNull(pathPattern, "pathPattern");
        routes.add(Route.builder().path(pathPattern).build());
        return self();
    }

    /**
     * Enables a successful CORS response with a {@code "null"} value for the CORS response header
     * {@code "Access-Control-Allow-Origin"}. Web browsers may set the {@code "Origin"} request header to
     * {@code "null"} if a resource is loaded from the local file system.
     *
     * @return {@code this} to support method chaining.
     */
    public SELF allowNullOrigin() {
        nullOriginAllowed = true;
        return self();
    }

    /**
     * Enables cookies to be added to CORS requests.
     * Calling this method will set the CORS {@code "Access-Control-Allow-Credentials"} response header
     * to {@code true}. By default, cookies are not included in CORS requests.
     *
     * <p>Please note that cookie support needs to be enabled on the client side as well.
     * The client needs to opt-in to send cookies by calling:
     * <pre>{@code
     * xhr.withCredentials = true;
     * }</pre>
     *
     * <p>The default value for {@code 'withCredentials'} is {@code false} in which case no cookies are sent.
     * Setting this to {@code true} will include cookies in cross origin requests.
     *
     * @return {@code this} to support method chaining.
     */
    public SELF allowCredentials() {
        credentialsAllowed = true;
        return self();
    }

    /**
     * Sets the CORS {@code "Access-Control-Max-Age"} response header and enables the
     * caching of the preflight response for the specified time. During this time no preflight
     * request will be made.
     *
     * @param maxAge the maximum time, in seconds, that the preflight response may be cached.
     * @return {@code this} to support method chaining.
     */
    public SELF maxAge(long maxAge) {
        checkState(maxAge > 0, "maxAge: %s (expected: > 0)", maxAge);
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
     *   <li>{@code Cache-Control}</li>
     *   <li>{@code Content-Language}</li>
     *   <li>{@code Content-Type}</li>
     *   <li>{@code Expires}</li>
     *   <li>{@code Last-Modified}</li>
     *   <li>{@code Pragma}</li>
     * </ul>
     *
     * <p>To expose other headers they need to be specified which is what this method enables by
     * adding the headers to the CORS {@code "Access-Control-Expose-Headers"} response header.
     *
     * @param headers the values to be added to the {@code "Access-Control-Expose-Headers"} response header
     * @return {@code this} to support method chaining.
     */
    public SELF exposeHeaders(CharSequence... headers) {
        requireNonNull(headers, "headers");
        return exposeHeaders(ImmutableList.copyOf(headers));
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
     *   <li>{@code Cache-Control}</li>
     *   <li>{@code Content-Language}</li>
     *   <li>{@code Content-Type}</li>
     *   <li>{@code Expires}</li>
     *   <li>{@code Last-Modified}</li>
     *   <li>{@code Pragma}</li>
     * </ul>
     *
     * <p>To expose other headers they need to be specified which is what this method enables by
     * adding the headers to the CORS {@code "Access-Control-Expose-Headers"} response header.
     *
     * @param headers the values to be added to the {@code "Access-Control-Expose-Headers"} response header
     * @return {@code this} to support method chaining.
     */
    public SELF exposeHeaders(Iterable<? extends CharSequence> headers) {
        requireNonNull(headers, "headers");
        final List<CharSequence> copied = new ArrayList<>();
        Iterables.addAll(copied, headers);
        checkArgument(!copied.isEmpty(), "headers should not be empty.");
        for (int i = 0; i < copied.size(); i++) {
            if (copied.get(i) == null) {
                throw new NullPointerException("headers[" + i + ']');
            }
        }

        copied.stream().map(HttpHeaderNames::of).forEach(exposedHeaders::add);
        return self();
    }

    /**
     * Specifies the allowed set of HTTP request methods that should be returned in the
     * CORS {@code "Access-Control-Allow-Methods"} response header.
     *
     * @param methods the {@link HttpMethod}s that should be allowed.
     * @return {@code this} to support method chaining.
     */
    public SELF allowRequestMethods(HttpMethod... methods) {
        requireNonNull(methods, "methods");
        return allowRequestMethods(ImmutableList.copyOf(methods));
    }

    /**
     * Specifies the allowed set of HTTP request methods that should be returned in the
     * CORS {@code "Access-Control-Allow-Methods"} response header.
     *
     * @param methods the {@link HttpMethod}s that should be allowed.
     * @return {@code this} to support method chaining.
     */
    public SELF allowRequestMethods(Iterable<HttpMethod> methods) {
        requireNonNull(methods, "methods");
        final List<HttpMethod> copied = new ArrayList<>();
        Iterables.addAll(copied, methods);
        checkArgument(!copied.isEmpty(), "methods should not be empty.");
        for (int i = 0; i < copied.size(); i++) {
            if (copied.get(i) == null) {
                throw new NullPointerException("methods[" + i + ']');
            }
        }
        allowedRequestMethods.addAll(copied);
        return self();
    }

    /**
     * Sets whether to allow all HTTP headers in the CORS {@code "Access-Control-Request-Headers"} request
     * header.
     *
     * <p>The server will set the CORS {@code "Access-Control-Allow-Headers"} to be as same as the CORS
     * {@code "Access-Control-Request-Headers"} header in the request if this property is {@code true}.
     * The default value of this property is {@code false}.
     *
     * @return {@code this} to support method chaining.
     */
    public SELF allowAllRequestHeaders(boolean allowAllRequestHeaders) {
        this.allowAllRequestHeaders = allowAllRequestHeaders;
        return self();
    }

    /**
     * Specifies the headers that should be returned in the CORS {@code "Access-Control-Allow-Headers"}
     * response header.
     *
     * <p>If a client specifies headers on the request, for example by calling:
     * <pre>{@code
     * xhr.setRequestHeader('My-Custom-Header', 'SomeValue');
     * }</pre>
     * The server will receive the above header name in the {@code "Access-Control-Request-Headers"} of the
     * preflight request. The server will then decide if it allows this header to be sent for the
     * real request (remember that a preflight is not the real request but a request asking the server
     * if it allows a request).
     *
     * @param headers the headers to be added to
     *                the preflight {@code "Access-Control-Allow-Headers"} response header.
     * @return {@code this} to support method chaining.
     */
    public SELF allowRequestHeaders(CharSequence... headers) {
        requireNonNull(headers, "headers");
        return allowRequestHeaders(ImmutableList.copyOf(headers));
    }

    /**
     * Specifies the headers that should be returned in the CORS {@code "Access-Control-Allow-Headers"}
     * response header.
     *
     * <p>If a client specifies headers on the request, for example by calling:
     * <pre>{@code
     * xhr.setRequestHeader('My-Custom-Header', 'SomeValue');
     * }</pre>
     * The server will receive the above header name in the {@code "Access-Control-Request-Headers"} of the
     * preflight request. The server will then decide if it allows this header to be sent for the
     * real request (remember that a preflight is not the real request but a request asking the server
     * if it allows a request).
     *
     * @param headers the headers to be added to
     *                the preflight {@code "Access-Control-Allow-Headers"} response header.
     * @return {@code this} to support method chaining.
     */
    public SELF allowRequestHeaders(Iterable<? extends CharSequence> headers) {
        requireNonNull(headers, "headers");
        final List<CharSequence> copied = new ArrayList<>();
        Iterables.addAll(copied, headers);
        checkArgument(!copied.isEmpty(), "headers should not be empty.");
        for (int i = 0; i < copied.size(); i++) {
            if (copied.get(i) == null) {
                throw new NullPointerException("headers[" + i + ']');
            }
        }
        copied.stream().map(HttpHeaderNames::of).forEach(allowedRequestHeaders::add);
        return self();
    }

    /**
     * Specifies HTTP response headers that should be added to a CORS preflight response.
     *
     * <p>An intermediary like a load balancer might require that a CORS preflight request
     * have certain headers set. This enables such headers to be added.
     *
     * @param name the name of the HTTP header.
     * @param values the values for the HTTP header.
     * @return {@code this} to support method chaining.
     */
    public SELF preflightResponseHeader(CharSequence name, Object... values) {
        requireNonNull(name, "name");
        requireNonNull(values, "values");
        checkArgument(values.length > 0, "values should not be empty.");
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                throw new NullPointerException("values[" + i + ']');
            }
        }
        preflightResponseHeaders.put(HttpHeaderNames.of(name),
                                     new ConstantValueSupplier(ImmutableList.copyOf(values)));
        return self();
    }

    /**
     * Specifies HTTP response headers that should be added to a CORS preflight response.
     *
     * <p>An intermediary like a load balancer might require that a CORS preflight request
     * have certain headers set. This enables such headers to be added.
     *
     * @param name the name of the HTTP header.
     * @param values the values for the HTTP header.
     * @return {@code this} to support method chaining.
     */
    public SELF preflightResponseHeader(CharSequence name, Iterable<?> values) {
        requireNonNull(name, "name");
        requireNonNull(values, "values");
        checkArgument(!Iterables.isEmpty(values), "values should not be empty.");
        final ImmutableList.Builder builder = new Builder();
        int i = 0;
        for (Object value : values) {
            if (value == null) {
                throw new NullPointerException("value[" + i + ']');
            }
            builder.add(value);
            i++;
        }
        preflightResponseHeaders.put(HttpHeaderNames.of(name), new ConstantValueSupplier(builder.build()));
        return self();
    }

    /**
     * Specifies HTTP response headers that should be added to a CORS preflight response.
     *
     * <p>An intermediary like a load balancer might require that a CORS preflight request
     * have certain headers set. This enables such headers to be added.
     *
     * <p>Some values must be dynamically created when the HTTP response is created, for
     * example the {@code "Date"} response header. This can be accomplished by using a {@link Supplier}
     * which will have its {@link Supplier#get()} method invoked when the HTTP response is created.
     *
     * @param name the name of the HTTP header.
     * @param valueSupplier a {@link Supplier} which will be invoked at HTTP response creation.
     * @return {@code this} to support method chaining.
     */
    public SELF preflightResponseHeader(CharSequence name, Supplier<?> valueSupplier) {
        requireNonNull(name, "name");
        requireNonNull(valueSupplier, "valueSupplier");
        preflightResponseHeaders.put(HttpHeaderNames.of(name), valueSupplier);
        return self();
    }

    /**
     * Specifies that no preflight response headers should be added to a preflight response.
     *
     * @return {@code this} to support method chaining.
     */
    public SELF disablePreflightResponseHeaders() {
        preflightResponseHeadersDisabled = true;
        return self();
    }

    /**
     * Returns a newly-created {@link CorsPolicy} based on the properties of this builder.
     */
    CorsPolicy build() {
        return new CorsPolicy(origins, originPredicate, routes, credentialsAllowed, maxAge,
                              nullOriginAllowed, exposedHeaders, allowAllRequestHeaders, allowedRequestHeaders,
                              allowedRequestMethods, preflightResponseHeadersDisabled,
                              preflightResponseHeaders);
    }

    @Override
    public String toString() {
        return CorsPolicy.toString(this, origins, routes,
                                   nullOriginAllowed, credentialsAllowed, maxAge, exposedHeaders,
                                   allowedRequestMethods, allowAllRequestHeaders, allowedRequestHeaders,
                                   preflightResponseHeaders);
    }
}
