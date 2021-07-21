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

import static com.google.common.base.MoreObjects.toStringHelper;

import java.util.function.BiPredicate;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Configuration for
 * <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-6.4">automatic redirection</a>.
 */
@UnstableApi
public final class RedirectConfig {

    private static final RedirectConfig defaultRedirectConfig = builder().build();

    private static final RedirectConfig disabledRedirectConfig = new RedirectConfig(
            (ctx, path) -> false, -1);

    /**
     * Returns the default {@link RedirectConfig}.
     */
    public static RedirectConfig of() {
        return defaultRedirectConfig;
    }

    /**
     * Returns the {@link RedirectConfig} that does not execute automatic redirection.
     */
    public static RedirectConfig disabled() {
        return disabledRedirectConfig;
    }

    /**
     * Returns a newly-created {@link RedirectConfigBuilder}.
     */
    public static RedirectConfigBuilder builder() {
        return new RedirectConfigBuilder();
    }

    private final int maxRedirects;
    @Nullable
    private final BiPredicate<ClientRequestContext, String> predicate;

    RedirectConfig(@Nullable BiPredicate<ClientRequestContext, String> predicate, int maxRedirects) {
        this.maxRedirects = maxRedirects;
        this.predicate = predicate;
    }

    /**
     * Returns the maximum number of automatic redirection that the client executes.
     */
    int maxRedirects() {
        return maxRedirects;
    }

    /**
     * Returns {@link BiPredicate} that tells whether the request can be redirected to the specified
     * domain or not.
     */
    @Nullable
    BiPredicate<ClientRequestContext, String> domainFilter() {
        return predicate;
    }

    @Override
    public String toString() {
        return toStringHelper(this).add("maxRedirects", maxRedirects)
                                   .add("predicate", predicate)
                                   .toString();
    }
}
