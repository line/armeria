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

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Service;

/**
 * Builds a new {@link CorsService} or its decorator function.
 */
public final class CorsServiceBuilder {

    /**
     * Creates a new builder with its origin set to '*'.
     */
    public static CorsPolicyBuilder forAnyOrigin() {
        return new CorsPolicyBuilder(new CorsServiceBuilder(true));
    }

    /**
     * Creates a new builder with the specified origin.
     */
    public static CorsPolicyBuilder forOrigin(final String origin) {
        requireNonNull(origin, "origin");

        if ("*".equals(origin)) {
            return forAnyOrigin();
        }
        return new CorsServiceBuilder(false).newOrigins(origin);
    }

    /**
     * Creates a new builder with the specified origins.
     */
    public static CorsPolicyBuilder forOrigins(final String... origins) {
        requireNonNull(origins, "origins");
        return new CorsServiceBuilder(false).newOrigins(origins);
    }

    final boolean anyOriginSupported;
    Set<CorsPolicy> policies;

    /**
     * Creates a new instance.
     *
     * @param anyOriginSupported .
     */
    CorsServiceBuilder(final boolean anyOriginSupported) {
        this.anyOriginSupported = anyOriginSupported;
        policies = new HashSet<CorsPolicy>();
    }

    /**
     * TODO: Add Javadocs.
     */
    public CorsServiceBuilder addPolicy(CorsPolicy policy) {
        checkState(!anyOriginSupported, "You can not add a new policy with any origin supported CORS service.");
        policies.add(policy);
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
    CorsPolicyBuilder newOrigins(final String... origins) {
        checkState(!anyOriginSupported, "You can not add a new policy with any origin supported CORS service.");
        return new CorsPolicyBuilder(this, origins);
    }

    @Override
    public String toString() {
        return CorsConfig.toString(this, true, anyOriginSupported, policies);
    }
}
