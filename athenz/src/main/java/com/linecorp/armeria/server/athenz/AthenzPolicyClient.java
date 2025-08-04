/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.server.athenz;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;

import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.NonNull;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.google.common.collect.ImmutableMap;
import com.yahoo.athenz.auth.token.AccessToken;
import com.yahoo.athenz.auth.token.RoleToken;
import com.yahoo.athenz.zpe.ZpeClient;
import com.yahoo.athenz.zpe.pkey.PublicKeyStore;
import com.yahoo.rdl.Struct;

import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.CommonPools;

final class AthenzPolicyClient implements ZpeClient {

    private final Cache<String, RoleToken> roleTokenCache;
    private final Cache<String, AccessToken> accessTokenCache;
    private final Map<String, AthenzPolicyLoader> policyLoaders;

    AthenzPolicyClient(ZtsBaseClient baseClient, AthenzPolicyConfig policyConfig, PublicKeyStore publicKeyStore,
                       int maxTokenCacheSize) {
        final Executor executor = CommonPools.blockingTaskExecutor();
        roleTokenCache = Caffeine.newBuilder()
                                 .maximumSize(maxTokenCacheSize)
                                 .expireAfter(new TokenExpiry<>(RoleToken::getExpiryTime))
                                 .executor(executor)
                                 .build();
        accessTokenCache = Caffeine.newBuilder()
                                   .maximumSize(maxTokenCacheSize)
                                   .expireAfter(new TokenExpiry<>(AccessToken::getExpiryTime))
                                   .executor(executor)
                                   .build();

        final ImmutableMap.Builder<String, AthenzPolicyLoader> builder =
                ImmutableMap.builderWithExpectedSize(policyConfig.domains().size());
        for (String domain : policyConfig.domains()) {
            builder.put(domain, new AthenzPolicyLoader(baseClient, domain, policyConfig, publicKeyStore));
        }
        policyLoaders = builder.buildKeepingLast();
    }

    @Override
    public void init(String domain) {
        for (AthenzPolicyLoader loader : policyLoaders.values()) {
            try {
                loader.init();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Override
    public void close() {}

    @Override
    public Map<String, RoleToken> getRoleTokenCacheMap() {
        return roleTokenCache.asMap();
    }

    @Override
    public Map<String, AccessToken> getAccessTokenCacheMap() {
        return accessTokenCache.asMap();
    }

    private AthenzAssertions assertionGroup(String domain) {
        final AthenzPolicyLoader policyLoader = policyLoaders.get(domain);
        if (policyLoader == null) {
            throw new AthenzPolicyException("No policy loader found for domain: " + domain);
        } else {
            return policyLoader.getNow();
        }
    }

    @Override
    public Map<String, List<Struct>> getRoleAllowAssertions(String domain) {
        return assertionGroup(domain).roleStandardAllowMap();
    }

    @Override
    public Map<String, List<Struct>> getWildcardAllowAssertions(String domain) {
        return assertionGroup(domain).roleWildcardAllowMap();
    }

    @Override
    public Map<String, List<Struct>> getRoleDenyAssertions(String domain) {
        return assertionGroup(domain).roleStandardDenyMap();
    }

    @Override
    public Map<String, List<Struct>> getWildcardDenyAssertions(String domain) {
        return assertionGroup(domain).roleWildcardDenyMap();
    }

    @Override
    public int getDomainCount() {
        return policyLoaders.size();
    }

    private static class TokenExpiry<V> implements Expiry<String, V> {

        private final ToLongFunction<V> expiryTimeFunction;

        TokenExpiry(ToLongFunction<V> expiryTimeFunction) {
            this.expiryTimeFunction = expiryTimeFunction;
        }

        @Override
        public long expireAfterCreate(@NonNull String key, @NonNull V value,
                                      long currentTime) {
            final long expiryTime = expiryTimeFunction.applyAsLong(value);
            if (expiryTime <= 0) {
                // If the expiry time is not set, we assume it never expires.
                return Long.MAX_VALUE;
            }
            final long now = System.currentTimeMillis() / 1000;
            return TimeUnit.SECONDS.toNanos(expiryTime - now);
        }

        @Override
        public long expireAfterUpdate(@NonNull String key, @NonNull V value,
                                      long currentTime, @NonNegative long currentDuration) {
            return currentDuration;
        }

        @Override
        public long expireAfterRead(@NonNull String key, @NonNull V value, long currentTime,
                                    @NonNegative long currentDuration) {
            return currentDuration;
        }
    }
}
