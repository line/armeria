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
 *
 */

package com.linecorp.armeria.server.athenz;

import static com.linecorp.armeria.common.util.UnmodifiableFuture.completedFuture;

import java.security.PublicKey;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.yahoo.athenz.auth.token.jwts.Key;
import com.yahoo.athenz.auth.util.Crypto;
import com.yahoo.athenz.zpe.pkey.PublicKeyStore;
import com.yahoo.athenz.zts.PublicKeyEntry;
import com.yahoo.athenz.zts.ServiceIdentity;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.util.AsyncLoader;

final class AthenzPublicKeyProvider implements PublicKeyStore {

    private static final Logger logger = LoggerFactory.getLogger(AthenzPublicKeyProvider.class);

    private final WebClient webClient;
    private final long minRetryInterval;
    private final AsyncLoader<Map<String, CompletableFuture<PublicKey>>> ztsKeyLoader;
    private final AsyncLoader<Map<String, CompletableFuture<PublicKey>>> zmsKeyLoader;
    private final String oauth2KeyPath;
    private volatile long lastReloadZtsJwkTime;
    private volatile long lastReloadZmsJwkTime;

    AthenzPublicKeyProvider(ZtsBaseClient ztsBaseClient, Duration refreshInterval, String oauth2KeyPath) {
        // TODO(ikhoon): Make minRetryInterval configurable.
        minRetryInterval = refreshInterval.toMillis() / 4;
        webClient = ztsBaseClient.webClient();
        this.oauth2KeyPath = oauth2KeyPath;
        ztsKeyLoader = AsyncLoader.<Map<String, CompletableFuture<PublicKey>>>builder(k -> fetchZtsKeys())
                                  .name("athenz-zts-key-loader")
                                  .refreshAfterLoad(refreshInterval)
                                  .build();
        zmsKeyLoader = AsyncLoader.<Map<String, CompletableFuture<PublicKey>>>builder(k -> fetchZmsKeys())
                                  .name("athenz-zms-key-loader")
                                  .refreshAfterLoad(refreshInterval)
                                  .build();
        ztsKeyLoader.load();
        zmsKeyLoader.load();
    }

    @Override
    public PublicKey getZtsKey(String keyId) {
        return getKey(ztsKeyLoader, keyId, true).join();
    }

    @Override
    public PublicKey getZmsKey(String keyId) {
        return getKey(zmsKeyLoader, keyId, false).join();
    }

    private CompletableFuture<PublicKey> getKey(
            AsyncLoader<Map<String, CompletableFuture<PublicKey>>> keyLoader, String keyId, boolean zts) {
        return keyLoader.load().thenCompose(keys -> {
            final CompletableFuture<PublicKey> publicKey = keys.get(keyId);
            if (publicKey != null) {
                return publicKey;
            }

            final long lastReloadJwkTime = zts ? lastReloadZtsJwkTime : lastReloadZmsJwkTime;
            if (!canReload(lastReloadJwkTime)) {
                return completedFuture(null);
            }

            // The keys may be rotated, so we need to reload the keys.
            return keyLoader.load(true).thenApply(keys0 -> {
                final CompletableFuture<PublicKey> publicKey0 = keys.get(keyId);
                if (publicKey0 != null) {
                    return publicKey0.join();
                } else {
                    return null;
                }
            });
        });
    }

    private CompletableFuture<Map<String, CompletableFuture<PublicKey>>> fetchZtsKeys() {
        return webClient
                .prepare()
                .get(oauth2KeyPath)
                .asJson(Keys.class)
                .execute()
                .thenApply(res -> {
                    final List<Key> keys = res.content().getKeys();
                    final Builder<String, CompletableFuture<PublicKey>> builder =
                            ImmutableMap.builderWithExpectedSize(keys.size());
                    for (Key key : keys) {
                        try {
                            builder.put(key.getKid(), completedFuture(key.getPublicKey()));
                        } catch (Exception ex) {
                            logger.warn("Unable to generate JSON Web Key for key-id {}", key.getKid(), ex);
                        }
                    }
                    lastReloadZtsJwkTime = System.currentTimeMillis();
                    return builder.buildKeepingLast();
                });
    }

    private CompletableFuture<Map<String, CompletableFuture<PublicKey>>> fetchZmsKeys() {
        return webClient
                .prepare()
                .get("/domain/sys.auth/service/zms")
                .asJson(ServiceIdentity.class)
                .execute()
                .thenApply(res -> {
                    final List<PublicKeyEntry> keys = res.content().getPublicKeys();
                    final Builder<String, CompletableFuture<PublicKey>> builder =
                            ImmutableMap.builderWithExpectedSize(keys.size());
                    for (PublicKeyEntry key : keys) {
                        try {
                            final PublicKey publicKey =
                                    Crypto.loadPublicKey(Crypto.ybase64DecodeString(key.getKey()));
                            builder.put(key.getId(), completedFuture(publicKey));
                        } catch (Exception ex) {
                            logger.warn("Unable to generate zms proprietary key for key-id {}",
                                        key.getId(), ex);
                        }
                    }
                    lastReloadZmsJwkTime = System.currentTimeMillis();
                    return builder.buildKeepingLast();
                });
    }

    private boolean canReload(long lastReloadJwkTime) {
        final long now = System.currentTimeMillis();
        final long millisDiff = now - lastReloadJwkTime;
        return millisDiff > minRetryInterval;
    }

    private static class Keys {
        private final List<Key> keys;

        @JsonCreator
        Keys(@JsonProperty("keys") List<Key> keys) {
            this.keys = keys;
        }

        List<Key> getKeys() {
            return keys;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("keys", keys)
                              .toString();
        }
    }
}
