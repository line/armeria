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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.server.HttpService;

/**
 * Builds a new {@link CorsService} or its decorator function.
 * <h2>Example</h2>
 * <pre>{@code
 * ServerBuilder sb = Server.builder();
 * sb.service("/cors", myService.decorate(
 *          CorsService.builder("http://example.com", "http://example2.com")
 *                     .shortCircuit()
 *                     .allowNullOrigin()
 *                     .allowCredentials()
 *                     .allowRequestMethods(HttpMethod.GET, HttpMethod.POST)
 *                     .allowRequestHeaders("allow_request_header1", "allow_request_header2")
 *                     .andForOrigins("http://example3.com")
 *                     .allowCredentials()
 *                     .allowRequestMethods(HttpMethod.GET)
 *                     .and()
 *                     .newDecorator()));
 * }</pre>
 */
public final class CorsServiceBuilder {

    private static final Logger logger = LoggerFactory.getLogger(CorsServiceBuilder.class);

    final boolean anyOriginSupported;
    final ChainedCorsPolicyBuilder firstPolicyBuilder;
    final List<CorsPolicy> policies = new ArrayList<>();

    boolean shortCircuit;

    /**
     * Creates a new instance for a {@link CorsService} with a {@link CorsPolicy} allowing {@code origins}.
     *
     */
    CorsServiceBuilder(List<String> origins) {
        anyOriginSupported = false;
        firstPolicyBuilder = new ChainedCorsPolicyBuilder(this, origins);
    }

    /**
     * Creates a new instance for a {@link CorsService} with a {@link CorsPolicy} allowing any origin.
     */
    CorsServiceBuilder() {
        anyOriginSupported = true;
        firstPolicyBuilder = new ChainedCorsPolicyBuilder(this);
    }

    private void ensureForNewPolicy() {
        checkState(!anyOriginSupported,
                   "You can not add more than one policy with any origin supported CORS service.");
    }

    /**
     * Adds a {@link CorsPolicy} instance in the service.
     */
    public CorsServiceBuilder addPolicy(CorsPolicy policy) {
        ensureForNewPolicy();
        policies.add(policy);
        return this;
    }

    /**
     * Adds a path pattern that this policy is supposed to be applied to.
     *
     * @param pathPattern the path pattern that this policy is supposed to be applied to
     * @throws IllegalArgumentException if the path pattern is not valid
     */
    public CorsServiceBuilder route(String pathPattern) {
        requireNonNull(pathPattern, "pathPattern");
        firstPolicyBuilder.route(pathPattern);
        return this;
    }

    /**
     * Enables a successful CORS response with a {@code "null"} value for the CORS response header
     * {@code "Access-Control-Allow-Origin"}. Web browsers may set the {@code "Origin"} request header to
     * {@code "null"} if a resource is loaded from the local file system.
     *
     * @return {@code this} to support method chaining.
     * @throws IllegalStateException if {@link #anyOriginSupported} is {@code true}.
     */
    public CorsServiceBuilder allowNullOrigin() {
        checkState(!anyOriginSupported,
                   "allowNullOrigin cannot be enabled with any origin supported CorsService.");
        firstPolicyBuilder.allowNullOrigin();
        return this;
    }

    /**
     * Enables cookies to be added to CORS requests.
     * Calling this method will set the CORS {@code "Access-Control-Allow-Credentials"} response header
     * to {@code true}. By default, cookies are not included in CORS requests.
     *
     * <p>Please note, that cookie support needs to be enabled on the client side as well.
     * The client needs to opt-in to send cookies by calling:
     * <pre>{@code
     * xhr.withCredentials = true;
     * }</pre>
     *
     * <p>The default value for {@code 'withCredentials'} is {@code false} in which case no cookies are sent.
     * Setting this to {@code true} will include cookies in cross origin requests.
     *
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder allowCredentials() {
        if (anyOriginSupported) {
            logger.warn(
                    "allowCredentials has been enabled for any origin (*). It will work properly" +
                    " but it would be better disabled or enabled with specified origins for security." +
                    " Visit https://www.w3.org/TR/cors/#supports-credentials for more information.");
        }
        firstPolicyBuilder.allowCredentials();
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
     * @throws IllegalStateException if {@link #anyOriginSupported} is {@code true}.
     */
    public CorsServiceBuilder shortCircuit() {
        checkState(!anyOriginSupported,
                   "shortCircuit cannot be enabled with any origin supported CorsService.");
        shortCircuit = true;
        return this;
    }

    /**
     * Sets the CORS {@code "Access-Control-Max-Age"} response header and enables the
     * caching of the preflight response for the specified time. During this time no preflight
     * request will be made.
     *
     * @param maxAge the maximum time, in seconds, that the preflight response may be cached.
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder maxAge(long maxAge) {
        firstPolicyBuilder.maxAge(maxAge);
        return this;
    }

    /**
     * Sets the CORS {@code "Access-Control-Max-Age"} response header and enables the
     * caching of the preflight response for the specified time. During this time no preflight
     * request will be made.
     *
     * @param maxAge the maximum time that the preflight response may be cached. Rounded to seconds.
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder maxAge(Duration maxAge) {
        requireNonNull(maxAge, "maxAge");
        return maxAge(TimeUnit.MILLISECONDS.toSeconds(maxAge.toMillis()));
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
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder exposeHeaders(CharSequence... headers) {
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
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder exposeHeaders(Iterable<? extends CharSequence> headers) {
        requireNonNull(headers, "headers");
        firstPolicyBuilder.exposeHeaders(headers);
        return this;
    }

    /**
     * Specifies the allowed set of HTTP request methods that should be returned in the
     * CORS {@code "Access-Control-Allow-Methods"} response header.
     *
     * @param methods the {@link HttpMethod}s that should be allowed.
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder allowRequestMethods(HttpMethod... methods) {
        requireNonNull(methods, "methods");
        return allowRequestMethods(ImmutableList.copyOf(methods));
    }

    /**
     * Specifies the allowed set of HTTP request methods that should be returned in the
     * CORS {@code "Access-Control-Allow-Methods"} response header.
     *
     * @param methods the {@link HttpMethod}s that should be allowed.
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder allowRequestMethods(Iterable<HttpMethod> methods) {
        requireNonNull(methods, "methods");
        firstPolicyBuilder.allowRequestMethods(methods);
        return this;
    }

    /**
     * Enables to allow all HTTP headers.
     *
     * <p>The server will set the CORS {@code "Access-Control-Allow-Headers"} to be as same as the CORS
     * {@code "Access-Control-Request-Headers"} header in the request.
     *
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder allowAllRequestHeaders() {
        firstPolicyBuilder.allowAllRequestHeaders();
        return this;
    }

    /**
     * Specifies the headers that should be returned in the CORS {@code "Access-Control-Allow-Headers"}
     * response header.
     *
     * <p>If a client specifies headers on the request, for example by calling:
     * <pre>{@code
     * xhr.setRequestHeader('My-Custom-Header', 'SomeValue');
     * }</pre>
     * the server will receive the above header name in the {@code "Access-Control-Request-Headers"} of the
     * preflight request. The server will then decide if it allows this header to be sent for the
     * real request (remember that a preflight is not the real request but a request asking the server
     * if it allows a request).
     *
     * @param headers the headers to be added to the preflight
     *                {@code "Access-Control-Allow-Headers"} response header.
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder allowRequestHeaders(CharSequence... headers) {
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
     * the server will receive the above header name in the {@code "Access-Control-Request-Headers"} of the
     * preflight request. The server will then decide if it allows this header to be sent for the
     * real request (remember that a preflight is not the real request but a request asking the server
     * if it allows a request).
     *
     * @param headers the headers to be added to the preflight
     *                {@code "Access-Control-Allow-Headers"} response header.
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder allowRequestHeaders(Iterable<? extends CharSequence> headers) {
        requireNonNull(headers, "headers");
        firstPolicyBuilder.allowRequestHeaders(headers);
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
    public CorsServiceBuilder preflightResponseHeader(CharSequence name, Object... values) {
        firstPolicyBuilder.preflightResponseHeader(name, values);
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
    public CorsServiceBuilder preflightResponseHeader(CharSequence name, Iterable<?> values) {
        firstPolicyBuilder.preflightResponseHeader(name, values);
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
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder preflightResponseHeader(CharSequence name, Supplier<?> valueSupplier) {
        firstPolicyBuilder.preflightResponseHeader(name, valueSupplier);
        return this;
    }

    /**
     * Specifies that no preflight response headers should be added to a preflight response.
     *
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder disablePreflightResponseHeaders() {
        firstPolicyBuilder.disablePreflightResponseHeaders();
        return this;
    }

    /**
     * Returns a newly-created {@link CorsService} based on the properties of this builder.
     */
    public CorsService build(HttpService delegate) {
        if (delegate.as(CorsService.class) != null) {
            throw new IllegalArgumentException(
                    "decorated with a " + CorsService.class.getSimpleName() + " already: " +
                    delegate);
        }

        return new CorsService(delegate, new CorsConfig(this));
    }

    /**
     * Returns a newly-created decorator that decorates an {@link HttpService} with a new {@link CorsService}
     * based on the properties of this builder.
     */
    public Function<? super HttpService, CorsService> newDecorator() {
        return this::build;
    }

    /**
     * Creates a new builder instance for a new {@link CorsPolicy}.
     *
     * @return {@link ChainedCorsPolicyBuilder} to support method chaining.
     */
    public ChainedCorsPolicyBuilder andForOrigins(String... origins) {
        requireNonNull(origins, "origins");
        return andForOrigins(ImmutableList.copyOf(origins));
    }

    /**
     * Creates a new builder instance for a new {@link CorsPolicy}.
     *
     * @return {@link ChainedCorsPolicyBuilder} to support method chaining.
     */
    public ChainedCorsPolicyBuilder andForOrigins(Iterable<String> origins) {
        requireNonNull(origins, "origins");
        ensureForNewPolicy();
        return new ChainedCorsPolicyBuilder(this, ImmutableList.copyOf(origins));
    }

    /**
     * Creates a new builder instance for a new {@link CorsPolicy}.
     *
     * @return {@link ChainedCorsPolicyBuilder} to support method chaining.
     */
    public ChainedCorsPolicyBuilder andForOrigin(String origin) {
        return andForOrigins(ImmutableList.of(origin));
    }

    @Override
    public String toString() {
        return CorsConfig.toString(this, anyOriginSupported, shortCircuit, policies);
    }
}
