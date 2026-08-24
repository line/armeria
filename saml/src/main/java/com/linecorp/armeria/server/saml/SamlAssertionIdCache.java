/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.server.saml;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A cache that tracks consumed SAML assertion IDs to prevent replay attacks.
 *
 * <p>The SAML 2.0 Web SSO Profile (section 4.1.4.5) requires that a service provider ensures
 * bearer assertions are used only once within the validity window. Implementations of this interface
 * must store consumed assertion IDs and reject duplicates.
 *
 * <p>The default implementation uses an in-process Caffeine cache, which is sufficient for
 * single-node deployments. For clustered (active-active) deployments, provide a custom
 * implementation backed by a shared store (e.g. Redis) to ensure replay protection across all nodes.
 *
 * @see SamlServiceProviderBuilder#assertionIdCache(SamlAssertionIdCache)
 */
@UnstableApi
@FunctionalInterface
public interface SamlAssertionIdCache {

    /**
     * Returns a new {@link SamlAssertionIdCache} backed by a local in-process Caffeine cache.
     * Consumed assertion IDs are retained for 10 minutes, which generously covers typical IdP
     * validity windows (2-5 minutes) plus clock skew margin.
     *
     * <p>Note: This implementation provides node-local protection only. In clustered deployments,
     * replays targeting different nodes will not be detected.
     */
    static SamlAssertionIdCache ofLocal() {
        return new CaffeineBasedSamlAssertionIdCache();
    }

    /**
     * Attempts to mark the specified assertion ID as consumed. Returns a {@link CompletableFuture}
     * that resolves to {@code true} if the ID was successfully recorded (i.e. it was not previously
     * consumed), or {@code false} if the ID has already been consumed (replay detected).
     *
     * <p>Implementations must be thread-safe. The {@code assertionId} is a composite key in the
     * format {@code "issuer:assertionId"}.
     *
     * @param assertionId the composite assertion ID to mark as consumed
     * @return a {@link CompletableFuture} resolving to {@code true} if the assertion ID was newly
     *         recorded, {@code false} if it was a replay
     */
    CompletableFuture<Boolean> tryConsume(String assertionId);
}
