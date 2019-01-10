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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Service;

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
            return forAnyOrigin();
        }
        return new CorsServiceBuilder(origin);
    }

    /**
     * Creates a new builder with the specified origins.
     */
    public static CorsServiceBuilder forOrigins(final String... origins) {
        requireNonNull(origins, "origins");
        return new CorsServiceBuilder(origins);
    }

    final boolean anyOriginSupported;

    private final CorsPolicyBuilder defaultPolicyBuilder;
    private Set<CorsPolicy> policies;
    @Nullable
    private CorsPolicy defaultPolicy;

    /**
     * Creates a new instance.
     *
     */
    CorsServiceBuilder(final String... origins) {
        anyOriginSupported = false;
        policies = new HashSet<>();
        defaultPolicyBuilder = new CorsPolicyBuilder(this, origins);
    }

    CorsServiceBuilder() {
        anyOriginSupported = true;
        policies = Collections.emptySet();
        defaultPolicyBuilder = new CorsPolicyBuilder(this);
    }

    private void ensureForNewPolicy() {
        checkState(!anyOriginSupported,
                   "You can not add more than one policy with any origin supported CORS service.");
    }

    Set<CorsPolicy> policies() {
        return new ImmutableSet.Builder<CorsPolicy>().add(defaultPolicy()).addAll(this.policies).build();
    }

    CorsPolicy defaultPolicy() {
        if (defaultPolicy == null) {
            defaultPolicy = defaultPolicyBuilder.build();
        }
        return defaultPolicy;
    }

    /**
     * TODO: Add javadocs and exception message.
     */
    private void defaultPolicyBuilderUpdated() {
        checkState(defaultPolicy == null, "");
    }

    /**
     * TODO: Add Javadocs.
     */
    public CorsServiceBuilder addPolicy(CorsPolicy policy) {
        ensureForNewPolicy();
        defaultPolicy();
        policies.add(policy);
        return this;
    }

    /**
     * Web browsers may set the 'Origin' request header to 'null' if a resource is loaded
     * from the local file system. Calling this method will enable a successful CORS response
     * with a {@code "null"} value for the the CORS response header 'Access-Control-Allow-Origin'.
     *
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder allowNullOrigin() {
        defaultPolicyBuilderUpdated();
        defaultPolicyBuilder.allowNullOrigin();
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
        defaultPolicyBuilderUpdated();
        defaultPolicyBuilder.allowCredentials();
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
        defaultPolicyBuilderUpdated();
        defaultPolicyBuilder.shortCircuit();
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
        defaultPolicyBuilderUpdated();
        defaultPolicyBuilder.maxAge(maxAge);
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
        defaultPolicyBuilderUpdated();
        defaultPolicyBuilder.exposeHeaders(headers);
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
        defaultPolicyBuilderUpdated();
        defaultPolicyBuilder.exposeHeaders(headers);
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
        defaultPolicyBuilderUpdated();
        defaultPolicyBuilder.allowRequestMethods(methods);
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
        defaultPolicyBuilderUpdated();
        defaultPolicyBuilder.allowRequestHeaders(headers);
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
        defaultPolicyBuilderUpdated();
        defaultPolicyBuilder.allowRequestHeaders(headers);
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
     * @return {@link CorsPolicyBuilder} to support method chaining.
     */
    public CorsServiceBuilder preflightResponseHeader(final String name, final Object... values) {
        defaultPolicyBuilderUpdated();
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
        defaultPolicyBuilderUpdated();
        defaultPolicyBuilder.preflightResponseHeader(name, values);
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
     * @return {@link CorsPolicyBuilder} to support method chaining.
     */
    public <T> CorsServiceBuilder preflightResponseHeader(final AsciiString name, final Iterable<T> values) {
        defaultPolicyBuilderUpdated();
        defaultPolicyBuilder.preflightResponseHeader(name, values);
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
        defaultPolicyBuilderUpdated();
        defaultPolicyBuilder.preflightResponseHeader(name, valueSupplier);
        return this;
    }

    /**
     * Specifies that no preflight response headers should be added to a preflight response.
     *
     * @return {@link CorsServiceBuilder} to support method chaining.
     */
    public CorsServiceBuilder disablePreflightResponseHeaders() {
        defaultPolicyBuilderUpdated();
        defaultPolicyBuilder.disablePreflightResponseHeaders();
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

    /**
     * TODO: Add Javadocs.
     */
    public CorsPolicyBuilder andForOrigins(final String... origins) {
        ensureForNewPolicy();
        return new CorsPolicyBuilder(this, origins);
    }

    @Override
    public String toString() {
        return CorsConfig.toString(this, true, anyOriginSupported, policies);
    }
}
