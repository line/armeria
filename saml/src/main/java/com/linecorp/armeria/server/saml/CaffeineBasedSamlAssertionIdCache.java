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

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.linecorp.armeria.common.util.UnmodifiableFuture;

/**
 * A {@link SamlAssertionIdCache} implementation backed by a local in-process Caffeine cache.
 *
 * <p>Once an assertion's {@code NotOnOrAfter} has passed, replays are already rejected by the
 * expiration check, so the cache only needs to retain IDs for the maximum assertion validity
 * period plus clock skew margin. 10 minutes is chosen to generously cover typical IdP validity
 * windows (2-5 minutes) and clock skew.
 */
final class CaffeineBasedSamlAssertionIdCache implements SamlAssertionIdCache {

    private static final Duration EXPIRY = Duration.ofMinutes(10);
    private static final long MAX_SIZE = 100_000;

    private final Cache<String, Boolean> cache;

    CaffeineBasedSamlAssertionIdCache() {
        cache = Caffeine.newBuilder()
                        .expireAfterWrite(EXPIRY)
                        .maximumSize(MAX_SIZE)
                        .build();
    }

    @Override
    public CompletableFuture<Boolean> tryConsume(String assertionId) {
        final boolean consumed = cache.asMap().putIfAbsent(assertionId, Boolean.TRUE) == null;
        return UnmodifiableFuture.completedFuture(consumed);
    }
}
