/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.client;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiPredicate;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A builder for {@link RedirectConfig}.
 */
@UnstableApi
public final class RedirectConfigBuilder {

    @VisibleForTesting
    static final BiPredicate<ClientRequestContext, String> allowAllDomains = (ctx, domain) -> true;

    static final BiPredicate<ClientRequestContext, String> allowSameDomain = (ctx, domain) -> {
        final Endpoint endpoint = ctx.endpoint();
        if (endpoint == null) {
            return false;
        }
        return endpoint.host().contains(domain);
    };

    private int maxRedirects = 19; // Widely used default value. https://stackoverflow.com/a/36041063/1736581

    private boolean isAllowingAllDomains;
    @Nullable
    private Set<String> allowedDomains;
    @Nullable
    private BiPredicate<ClientRequestContext, String> predicate;

    RedirectConfigBuilder() {}

    /**
     * Sets the maximum number of automatic redirection that the client executes.
     */
    public RedirectConfigBuilder maxRedirects(int maxRedirects) {
        checkArgument(maxRedirects > 0, "maxRedirects: %s (expected: > 0)", maxRedirects);
        this.maxRedirects = maxRedirects;
        return this;
    }

    /**
     * Allows automatic redirection for all domains.
     * If the {@link WebClient} is created without a base URI, the {@link WebClient} executes
     * automatic redirection for all domains by default. If the {@link WebClient} is created with a base URI,
     * automatic redirection is executed for the domain of the base URI by default.
     */
    public RedirectConfigBuilder allowAllDomains() {
        isAllowingAllDomains = true;
        return this;
    }

    /**
     * Sets the domains that are allowed for automatic redirection.
     * If the {@link WebClient} is created without a base URI, the {@link WebClient} executes
     * automatic redirection for all domains by default. If the {@link WebClient} is created with a base URI,
     * automatic redirection is executed for the domain of the base URI by default.
     */
    public RedirectConfigBuilder allowDomains(String... domains) {
        return allowDomains(ImmutableList.copyOf(requireNonNull(domains, "domains")));
    }

    /**
     * Sets the domains that are allowed for automatic redirection.
     * If the {@link WebClient} is created without a base URI, the {@link WebClient} executes
     * automatic redirection for all domains by default. If the {@link WebClient} is created with a base URI,
     * automatic redirection is executed for the domain of the base URI by default.
     */
    public RedirectConfigBuilder allowDomains(Iterable<String> domains) {
        requireNonNull(domains, "domains");
        if (allowedDomains == null) {
            allowedDomains = new HashSet<>();
        }
        allowedDomains.addAll(ImmutableList.copyOf(domains));
        return this;
    }

    /**
     * Sets the {@link BiPredicate} that returns {@code true} if the given redirect URI is allowed for
     * automatic redirection.
     * If the {@link WebClient} is created without a base URI, the {@link WebClient} executes
     * automatic redirection for all domains by default. If the {@link WebClient} is created with a base URI,
     * automatic redirection is executed for the domain of the base URI by default.
     */
    public RedirectConfigBuilder allow(BiPredicate<ClientRequestContext, String> predicate) {
        requireNonNull(predicate, "predicate");
        if (this.predicate == null) {
            this.predicate = predicate;
        } else {
            this.predicate = this.predicate.or(predicate);
        }
        return this;
    }

    /**
     * Returns a newly-created {@link RedirectConfig} based on the properties set so far.
     */
    public RedirectConfig build() {
        BiPredicate<ClientRequestContext, String> predicate;
        if (isAllowingAllDomains) {
            predicate = allowAllDomains;
        } else {
            final BiPredicate<ClientRequestContext, String> allowedDomains = allowedDomains();
            if (allowedDomains != null) {
                predicate = allowedDomains;
                if (this.predicate != null) {
                    predicate = predicate.or(this.predicate);
                }
            } else {
                predicate = this.predicate;
            }
        }

        return new RedirectConfig(predicate, maxRedirects);
    }

    @Nullable
    private BiPredicate<ClientRequestContext, String> allowedDomains() {
        if (allowedDomains != null) {
            final Set<String> allowedDomains0 = ImmutableSet.copyOf(allowedDomains);
            return (ctx, domain) -> {
                for (String d : allowedDomains0) {
                    if (domain.contains(d)) {
                        return true;
                    }
                }
                return false;
            };
        }
        return null;
    }
}
