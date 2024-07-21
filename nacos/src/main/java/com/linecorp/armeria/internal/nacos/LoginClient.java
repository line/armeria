/*
 * Copyright 2024 LY Corporation

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
package com.linecorp.armeria.internal.nacos;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpEntity;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A Nacos client that is responsible for
 * <a href="https://nacos.io/en-us/docs/auth.html">Nacos Authentication</a>.
 */
final class LoginClient extends SimpleDecoratingHttpClient {
    private static final String NACOS_ACCESS_TOKEN_CACHE_KEY = "NACOS_ACCESS_TOKEN_CACHE_KEY";

    static Function<? super HttpClient, LoginClient> newDecorator(WebClient webClient,
                                                                         String username, String password) {
        return delegate -> new LoginClient(delegate, webClient, username, password);
    }

    private final HttpClient delegate;
    private final WebClient webClient;
    private final String queryParamsForLogin;

    // TODO: Replace the caffeine AsyncLoadingCache with internally implemented AsyncLoader, if #5590 merged.
    private final AsyncLoadingCache<String, LoginResult> tokenCache =
            Caffeine.newBuilder()
                    .maximumSize(1)
                    .expireAfter(
                            new Expiry<String, LoginResult>() {
                                @Override
                                public long expireAfterCreate(String key, LoginResult loginResult,
                                                              long currentTime) {
                                    return ttlToNanoSeconds(loginResult.tokenTtl);
                                }

                                @Override
                                public long expireAfterUpdate(String key, LoginResult loginResult,
                                                              long currentTime, long currentDuration) {
                                    return ttlToNanoSeconds(loginResult.tokenTtl);
                                }

                                @Override
                                public long expireAfterRead(String key, LoginResult loginResult,
                                                            long currentTime, long currentDuration) {
                                    return currentDuration;
                                }

                                private long ttlToNanoSeconds(int ttlInSeconds) {
                                    return ttlInSeconds * 1_000_000_000L;
                                }
                            })
                    .buildAsync((key, executor) -> {
                        try {
                            return loginInternal();
                        } catch (Exception e) {
                            throw new RuntimeException(
                                    e);
                        }
                    });

    LoginClient(HttpClient delegate, WebClient webClient, String username, String password) {
        super(delegate);

        this.delegate = requireNonNull(delegate, "delegate");
        this.webClient = requireNonNull(webClient, "webClient");
        queryParamsForLogin = QueryParams.builder()
                                         .add("username", requireNonNull(username, "username"))
                                         .add("password", requireNonNull(password, "password"))
                                         .toQueryString();
    }

    private CompletableFuture<String> login() {
        return tokenCache.get(NACOS_ACCESS_TOKEN_CACHE_KEY)
                         .thenApply(loginResult -> loginResult.accessToken);
    }

    private CompletableFuture<LoginResult> loginInternal() {
        return webClient.prepare().post("/v1/auth/login")
                        .content(MediaType.FORM_DATA, queryParamsForLogin)
                        .asJson(LoginResult.class)
                        .as(HttpEntity::content)
                        .execute();
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) {
        final CompletableFuture<HttpResponse> future = login().thenApply(accessToken -> {
            try {
                final HttpRequest newReq = req.mapHeaders(headers -> headers.toBuilder()
                                                                            .set(HttpHeaderNames.AUTHORIZATION,
                                                                                 "Bearer " + accessToken)
                                                                            .build());
                ctx.updateRequest(newReq);
                return delegate.execute(ctx, newReq);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        return HttpResponse.of(future);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class LoginResult {
        private final String accessToken;

        private final int tokenTtl;

        @Nullable
        private final Boolean globalAdmin;

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
