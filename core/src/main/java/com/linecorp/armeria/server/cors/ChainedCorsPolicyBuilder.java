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

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.function.Supplier;

import com.linecorp.armeria.common.HttpMethod;

/**
 * Builds a new {@link CorsPolicy}.
 *
 * <p>This class can only be created through the {@link CorsServiceBuilder#andForOrigins(String...)} or
 * {@link CorsServiceBuilder#andForOrigin(String)} method of the {@link CorsServiceBuilder}.
 *
 * <p>Calling {@link #and()} method will return the control to {@link CorsServiceBuilder}.
 */
public final class ChainedCorsPolicyBuilder extends AbstractCorsPolicyBuilder {

    private final CorsServiceBuilder serviceBuilder;

    ChainedCorsPolicyBuilder(CorsServiceBuilder builder) {
        requireNonNull(builder, "builder");
        serviceBuilder = builder;
    }

    ChainedCorsPolicyBuilder(CorsServiceBuilder builder, List<String> origins) {
        super(origins);
        requireNonNull(builder, "builder");
        serviceBuilder = builder;
    }

    /**
     * Returns the parent {@link CorsServiceBuilder}.
     */
    public CorsServiceBuilder and() {
        return serviceBuilder.addPolicy(build());
    }

    /**
     * Creates a new instance of {@link ChainedCorsPolicyBuilder}
     * added to the parent {@link CorsServiceBuilder}.
     *
     * @return the created instance.
     */
    public ChainedCorsPolicyBuilder andForOrigins(String... origins) {
        return and().andForOrigins(origins);
    }

    /**
     * Creates a new instance of {@link ChainedCorsPolicyBuilder}
     * added to the parent {@link CorsServiceBuilder}.
     *
     * @return the created instance.
     */
    public ChainedCorsPolicyBuilder andForOrigin(String origin) {
        return and().andForOrigin(origin);
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public ChainedCorsPolicyBuilder route(String pathPattern) {
        return (ChainedCorsPolicyBuilder) super.route(pathPattern);
    }

    @Override
    public ChainedCorsPolicyBuilder allowNullOrigin() {
        return (ChainedCorsPolicyBuilder) super.allowNullOrigin();
    }

    @Override
    public ChainedCorsPolicyBuilder allowCredentials() {
        return (ChainedCorsPolicyBuilder) super.allowCredentials();
    }

    @Override
    public ChainedCorsPolicyBuilder maxAge(long maxAge) {
        return (ChainedCorsPolicyBuilder) super.maxAge(maxAge);
    }

    @Override
    public ChainedCorsPolicyBuilder exposeHeaders(CharSequence... headers) {
        return (ChainedCorsPolicyBuilder) super.exposeHeaders(headers);
    }

    @Override
    public ChainedCorsPolicyBuilder exposeHeaders(Iterable<? extends CharSequence> headers) {
        return (ChainedCorsPolicyBuilder) super.exposeHeaders(headers);
    }

    @Override
    public ChainedCorsPolicyBuilder allowRequestMethods(HttpMethod... methods) {
        return (ChainedCorsPolicyBuilder) super.allowRequestMethods(methods);
    }

    @Override
    public ChainedCorsPolicyBuilder allowRequestMethods(Iterable<HttpMethod> methods) {
        return (ChainedCorsPolicyBuilder) super.allowRequestMethods(methods);
    }

    @Override
    public ChainedCorsPolicyBuilder allowAllRequestHeaders() {
        return (ChainedCorsPolicyBuilder) super.allowAllRequestHeaders();
    }

    @Override
    public ChainedCorsPolicyBuilder allowAllRequestHeaders(boolean useWildcard) {
        return (ChainedCorsPolicyBuilder) super.allowAllRequestHeaders(useWildcard);
    }

    @Override
    public ChainedCorsPolicyBuilder allowRequestHeaders(CharSequence... headers) {
        return (ChainedCorsPolicyBuilder) super.allowRequestHeaders(headers);
    }

    @Override
    public ChainedCorsPolicyBuilder allowRequestHeaders(Iterable<? extends CharSequence> headers) {
        return (ChainedCorsPolicyBuilder) super.allowRequestHeaders(headers);
    }

    @Override
    public ChainedCorsPolicyBuilder preflightResponseHeader(CharSequence name, Object... values) {
        return (ChainedCorsPolicyBuilder) super.preflightResponseHeader(name, values);
    }

    @Override
    public ChainedCorsPolicyBuilder preflightResponseHeader(CharSequence name, Iterable<?> values) {
        return (ChainedCorsPolicyBuilder) super.preflightResponseHeader(name, values);
    }

    @Override
    public ChainedCorsPolicyBuilder preflightResponseHeader(CharSequence name, Supplier<?> valueSupplier) {
        return (ChainedCorsPolicyBuilder) super.preflightResponseHeader(name, valueSupplier);
    }

    @Override
    public ChainedCorsPolicyBuilder disablePreflightResponseHeaders() {
        return (ChainedCorsPolicyBuilder) super.disablePreflightResponseHeaders();
    }
}
