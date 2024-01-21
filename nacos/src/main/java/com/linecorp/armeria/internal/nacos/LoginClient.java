/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.armeria.internal.nacos;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import org.checkerframework.checker.nullness.qual.NonNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpEntity;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A Nacos client that is responsible for
 * <a href="https://nacos.io/en-us/docs/auth.html">Nacos Authentication</a>.
 */
final class LoginClient {

    static LoginClient of(NacosClient nacosClient, String username, String password) {
        return new LoginClient(nacosClient, username, password);
    }

    private static final String NACOS_ACCESS_TOKEN_CACHE_KEY = "NACOS_ACCESS_TOKEN_CACHE_KEY";
    private final AsyncLoadingCache<String, LoginResult> tokenCache = Caffeine.newBuilder()
            .maximumSize(1)
            .expireAfter(new Expiry<String, LoginResult>() {
                @Override
                public long expireAfterCreate(@NonNull String key, @NonNull LoginResult loginResult,
                                              long currentTime) {
                    return loginResult.tokenTtl.longValue();
                }

                @Override
                public long expireAfterUpdate(@NonNull String key, @NonNull LoginResult loginResult,
                                              long currentTime, long currentDuration) {
                    return loginResult.tokenTtl.longValue();
                }

                @Override
                public long expireAfterRead(@NonNull String key, @NonNull LoginResult loginResult,
                                            long currentTime, long currentDuration) {
                    return currentDuration;
                }
            })
            .buildAsync((key, executor) -> loginInternal());

    private final WebClient webClient;

    private final String username;

    private final String password;

    LoginClient(NacosClient nacosClient, String username, String password) {
        requireNonNull(nacosClient, "nacosClient");
        webClient = nacosClient.nacosWebClient();

        this.username = requireNonNull(username, "username");
        this.password = requireNonNull(password, "password");
    }

    public CompletableFuture<String> login() {
        return tokenCache.get(NACOS_ACCESS_TOKEN_CACHE_KEY)
                .thenApply(loginResult -> loginResult.accessToken);
    }

    private CompletableFuture<LoginResult> loginInternal() {
        return webClient.prepare().post("/v1/auth/login")
                .content(MediaType.FORM_DATA,
                        QueryParams.builder()
                                .add("username", username)
                                .add("password", password)
                                .toQueryString())
                .asJson(LoginResult.class)
                .as(HttpEntity::content)
                .execute();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class LoginResult {
        String accessToken;

        Integer tokenTtl;

        @Nullable
        Boolean globalAdmin;

        @JsonCreator
        LoginResult(@JsonProperty("accessToken") String accessToken, @JsonProperty("tokenTtl") Integer tokenTtl,
                    @JsonProperty("globalAdmin") @Nullable Boolean globalAdmin) {
            this.accessToken = accessToken;
            this.tokenTtl = tokenTtl;
            this.globalAdmin = globalAdmin;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .omitNullValues()
                    .add("accessToken", accessToken)
                    .add("tokenTtl", tokenTtl)
                    .add("globalAdmin", globalAdmin)
                    .toString();
        }
    }
}
