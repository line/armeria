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

import java.util.List;
import java.util.function.Supplier;

import com.google.common.base.Ascii;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.CorsHeaderUtil;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RoutingContext;

/**
 * <a href="https://en.wikipedia.org/wiki/Cross-origin_resource_sharing">Cross-Origin Resource Sharing
 * (CORS)</a> configuration.
 *
 * @see CorsServiceBuilder
 * @see CorsService#config()
 */
public final class CorsConfig {

    private final boolean anyOriginSupported;
    private final boolean shortCircuit;
    private final List<CorsPolicy> policies;

    CorsConfig(CorsServiceBuilder builder) {
        anyOriginSupported = builder.anyOriginSupported;
        shortCircuit = builder.shortCircuit;
        policies = new Builder<CorsPolicy>()
                .add(builder.firstPolicyBuilder.build())
                .addAll(builder.policies)
                .build();
    }

    /**
     * Determines whether a wildcard origin, '*', is supported.
     *
     * @return {@code true} if any origin is allowed.
     */
    public boolean isAnyOriginSupported() {
        return anyOriginSupported;
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
     * Returns the policies.
     */
    public List<CorsPolicy> policies() {
        return policies;
    }

    /**
     * Returns the policy for the specified {@code origin}.
     *
     * @return {@link CorsPolicy} which allows the {@code origin},
     *         {@code null} if the {@code origin} is {@code null} or not allowed in any policy.
     */
    @Nullable
    public CorsPolicy getPolicy(@Nullable String origin, RoutingContext routingContext) {
        requireNonNull(routingContext, "routingContext");
        if (origin == null) {
            return null;
        }

        if (isAnyOriginSupported()) {
            return Iterables.getFirst(policies, null);
        }

        final String lowerCaseOrigin = Ascii.toLowerCase(origin);
        final boolean isNullOrigin = CorsHeaderUtil.NULL_ORIGIN.equals(lowerCaseOrigin);
        for (final CorsPolicy policy : policies) {
            if (isNullOrigin && policy.isNullOriginAllowed() &&
                isPathMatched(policy, routingContext)) {
                return policy;
            } else if (!isNullOrigin && isPathMatched(policy, routingContext)) {
                if (policy.originPredicate().test(lowerCaseOrigin)) {
                    return policy;
                }
            }
        }
        return null;
    }

    private static boolean isPathMatched(CorsPolicy policy, RoutingContext routingContext) {
        final List<Route> routes = policy.routes();
        // We do not consider the score of the routing result for simplicity. It'd be enough to find
        // whether the path is matched or not.
        return routes.isEmpty() ||
               routes.stream().anyMatch(route -> route.apply(routingContext, false).isPresent());
    }

    @Override
    public String toString() {
        return toString(this, anyOriginSupported, shortCircuit, policies);
    }

    static String toString(Object obj, boolean anyOriginSupported, boolean shortCircuit,
                           List<CorsPolicy> policies) {
        return MoreObjects.toStringHelper(obj)
                          .add("policies", policies)
                          .add("shortCircuit", shortCircuit)
                          .add("anyOriginSupported", anyOriginSupported)
                          .toString();
    }

    /**
     * This class is used for preflight HTTP response values that do not need to be
     * generated, but instead the value is "static" in that the same value will be returned
     * for each call.
     */
    static final class ConstantValueSupplier implements Supplier<Object> {

        static final ConstantValueSupplier ZERO = new ConstantValueSupplier("0");

        private final Object value;

        ConstantValueSupplier(Object value) {
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
}
