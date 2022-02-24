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
package com.linecorp.armeria.client.redirect;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.internal.client.RedirectingClientUtil.allowAllDomains;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiPredicate;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.AbstractClientOptionsBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A builder for {@link RedirectConfig}.
 *
 * @see AbstractClientOptionsBuilder#followRedirects(RedirectConfig)
 */
@UnstableApi
public final class RedirectConfigBuilder {

    private int maxRedirects = 19; // Widely used default value. https://stackoverflow.com/a/36041063/1736581

    @Nullable
    private Set<SessionProtocol> allowedProtocols;
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
     * Sets the {@link SessionProtocol}s that are allowed for automatic redirection.
     * Only {@link SessionProtocol#HTTP} and {@link SessionProtocol#HTTPS} can be set.
     *
     * <p>When the allowed {@link SessionProtocol}s are not set, {@link SessionProtocol#HTTPS} is set
     * by default. If the {@link WebClient} is created <b>with</b> a base URI that has
     * {@link SessionProtocol#HTTP}, {@link SessionProtocol#HTTP} is also set.
     */
    public RedirectConfigBuilder allowProtocols(SessionProtocol... protocols) {
        requireNonNull(protocols, "protocols");
        return allowProtocols(ImmutableSet.copyOf(protocols));
    }

    /**
     * Sets the {@link SessionProtocol}s that are allowed for automatic redirection.
     * Only {@link SessionProtocol#HTTP} and {@link SessionProtocol#HTTPS} can be set.
     *
     * <p>When the allowed {@link SessionProtocol}s are not set, {@link SessionProtocol#HTTPS} is set
     * by default. If the {@link WebClient} is created <b>with</b> a base URI that has
     * {@link SessionProtocol#HTTP}, {@link SessionProtocol#HTTP} is also set.
     */
    public RedirectConfigBuilder allowProtocols(Iterable<SessionProtocol> protocols) {
        requireNonNull(protocols, "protocols");
        for (SessionProtocol protocol : protocols) {
            if (!(protocol == SessionProtocol.HTTP || protocol == SessionProtocol.HTTPS)) {
                throw new IllegalArgumentException(
                        "protocol: " + protocol +
                        " (expected: " + SessionProtocol.HTTP + " or " + SessionProtocol.HTTPS + ')');
            }
        }
        allowedProtocols = ImmutableSet.copyOf(protocols);
        return this;
    }

    /**
     * Allows automatic redirection to all domains.
     *
     * <p>If the {@link WebClient} is created <b>without</b> a base URI, the {@link WebClient} executes
     * automatic redirection to all domains by default.
     * If the {@link WebClient} is created <b>with</b> a base URI, automatic redirection is executed to
     * the domain of the base URI by default.
     */
    public RedirectConfigBuilder allowAllDomains() {
        isAllowingAllDomains = true;
        return this;
    }

    /**
     * Sets the domains that are allowed for automatic redirection. If the
     * {@linkplain URI#getHost() host component} of a redirection URI equals to the specified domains,
     * automatic redirection is executed.
     *
     * <p>If the {@link WebClient} is created <b>without</b> a base URI, the {@link WebClient} executes
     * automatic redirection to all domains by default.
     * If the {@link WebClient} is created <b>with</b> a base URI, automatic redirection is executed to
     * the domain of the base URI by default.
     */
    public RedirectConfigBuilder allowDomains(String... domains) {
        return allowDomains(ImmutableList.copyOf(requireNonNull(domains, "domains")));
    }

    /**
     * Sets the domains that are allowed for automatic redirection. If the
     * {@linkplain URI#getHost() host component} of a redirection URI equals to the specified domains,
     * automatic redirection is executed.
     *
     * <p>If the {@link WebClient} is created <b>without</b> a base URI, the {@link WebClient} executes
     * automatic redirection to all domains by default.
     * If the {@link WebClient} is created <b>with</b> a base URI, automatic redirection is executed to
     * the domain of the base URI by default.
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
     * Sets the {@link BiPredicate} that returns {@code true} if the {@linkplain URI#getHost() host component}
     * of a redirection URI is allowed for automatic redirection.
     *
     * <p>If the {@link WebClient} is created <b>without</b> a base URI, the {@link WebClient} executes
     * automatic redirection to all domains by default.
     * If the {@link WebClient} is created <b>with</b> a base URI, automatic redirection is executed to
     * the domain of the base URI by default.
     */
    public RedirectConfigBuilder allowDomains(
            BiPredicate<? super ClientRequestContext, ? super String> predicate) {
        requireNonNull(predicate, "predicate");
        if (this.predicate == null) {
            //noinspection unchecked
            this.predicate = (BiPredicate<ClientRequestContext, String>) predicate;
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

        return new RedirectConfig(allowedProtocols, predicate, maxRedirects);
    }

    @Nullable
    private BiPredicate<ClientRequestContext, String> allowedDomains() {
        if (allowedDomains != null) {
            final Set<String> allowedDomains0 = ImmutableSet.copyOf(allowedDomains);
            return (ctx, domain) -> {
                for (String d : allowedDomains0) {
                    // Use `equals()` to prevent Open Redirects.
                    // See https://www.acunetix.com/blog/web-security-zone/what-are-open-redirects/
                    if (domain.equals(d)) {
                        return true;
                    }
                }
                return false;
            };
        }
        return null;
    }
}
