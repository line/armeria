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

import java.util.function.Supplier;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpMethod;

/**
 * Builds a new {@link CorsPolicy}.
 * <h2>Example</h2>
 * <pre>{@code
 * CorsPolicyBuilder cb = CorsPolicy.builder("http://example.com");
 * cb.allowRequestMethods(HttpMethod.POST, HttpMethod.GET)
 *   .allowRequestHeaders("allow_request_header")
 *   .exposeHeaders("expose_header_1", "expose_header_2")
 *   .preflightResponseHeader("x-preflight-cors", "Hello CORS");
 * CorsPolicy policy = cb.build();
 * }</pre>
 *
 */
public final class CorsPolicyBuilder extends AbstractCorsPolicyBuilder {

    CorsPolicyBuilder() {}

    CorsPolicyBuilder(String... origins) {
        super(ImmutableList.copyOf(origins));
    }

    CorsPolicyBuilder(Iterable<String> origins) {
        super(ImmutableList.copyOf(origins));
    }

    /**
     * Returns a newly-created {@link CorsPolicy} based on the properties of this builder.
     */
    @Override
    public CorsPolicy build() {
        return super.build();
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public CorsPolicyBuilder route(String pathPattern) {
        return (CorsPolicyBuilder) super.route(pathPattern);
    }

    @Override
    public CorsPolicyBuilder allowNullOrigin() {
        return (CorsPolicyBuilder) super.allowNullOrigin();
    }

    @Override
    public CorsPolicyBuilder allowCredentials() {
        return (CorsPolicyBuilder) super.allowCredentials();
    }

    @Override
    public CorsPolicyBuilder maxAge(long maxAge) {
        return (CorsPolicyBuilder) super.maxAge(maxAge);
    }

    @Override
    public CorsPolicyBuilder exposeHeaders(CharSequence... headers) {
        return (CorsPolicyBuilder) super.exposeHeaders(headers);
    }

    @Override
    public CorsPolicyBuilder exposeHeaders(Iterable<? extends CharSequence> headers) {
        return (CorsPolicyBuilder) super.exposeHeaders(headers);
    }

    @Override
    public CorsPolicyBuilder allowRequestMethods(HttpMethod... methods) {
        return (CorsPolicyBuilder) super.allowRequestMethods(methods);
    }

    @Override
    public CorsPolicyBuilder allowRequestMethods(Iterable<HttpMethod> methods) {
        return (CorsPolicyBuilder) super.allowRequestMethods(methods);
    }

    @Override
    public CorsPolicyBuilder allowAllRequestHeaders() {
        return (CorsPolicyBuilder) super.allowAllRequestHeaders();
    }

    @Override
    public CorsPolicyBuilder allowAllRequestHeaders(boolean useWildcard) {
        return (CorsPolicyBuilder) super.allowAllRequestHeaders(useWildcard);
    }

    @Override
    public CorsPolicyBuilder allowRequestHeaders(CharSequence... headers) {
        return (CorsPolicyBuilder) super.allowRequestHeaders(headers);
    }

    @Override
    public CorsPolicyBuilder allowRequestHeaders(Iterable<? extends CharSequence> headers) {
        return (CorsPolicyBuilder) super.allowRequestHeaders(headers);
    }

    @Override
    public CorsPolicyBuilder preflightResponseHeader(CharSequence name, Object... values) {
        return (CorsPolicyBuilder) super.preflightResponseHeader(name, values);
    }

    @Override
    public CorsPolicyBuilder preflightResponseHeader(CharSequence name, Iterable<?> values) {
        return (CorsPolicyBuilder) super.preflightResponseHeader(name, values);
    }

    @Override
    public CorsPolicyBuilder preflightResponseHeader(CharSequence name, Supplier<?> valueSupplier) {
        return (CorsPolicyBuilder) super.preflightResponseHeader(name, valueSupplier);
    }

    @Override
    public CorsPolicyBuilder disablePreflightResponseHeaders() {
        return (CorsPolicyBuilder) super.disablePreflightResponseHeaders();
    }
}
