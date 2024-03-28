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
import java.util.function.Function;
import java.util.function.Supplier;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
import com.linecorp.armeria.common.util.UnmodifiableFuture;

/**
 * A Nacos client that is responsible for
 * <a href="https://nacos.io/en-us/docs/auth.html">Nacos Authentication</a>.
 */
final class LoginClient extends SimpleDecoratingHttpClient {
    public static Function<? super HttpClient, LoginClient> newDecorator(WebClient webClient,
                                                                         String username, String password) {
        return delegate -> new LoginClient(delegate, webClient, username, password);
    }

    private final HttpClient delegate;

    private final WebClient webClient;

    private final String queryParamsForLogin;

    private final CachedLoginResult cachedLoginResult = new CachedLoginResult();

    LoginClient(HttpClient delegate, WebClient webClient, String username, String password) {
        super(delegate);

        this.delegate = requireNonNull(delegate, "delegate");
        this.webClient = requireNonNull(webClient, "webClient");
        this.queryParamsForLogin = QueryParams.builder()
                .add("username", requireNonNull(username, "username"))
                .add("password", requireNonNull(password, "password"))
                .toQueryString();
    }

    private CompletableFuture<LoginResult> login() {
        return webClient.prepare().post("/v1/auth/login")
                .content(MediaType.FORM_DATA, queryParamsForLogin)
                .asJson(LoginResult.class)
                .as(HttpEntity::content)
                .execute();
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) {
        final CompletableFuture<HttpResponse> future = cachedLoginResult.get().thenApply(accessToken -> {
            try {
                return delegate.execute(ctx, req.mapHeaders(headers -> headers.toBuilder()
                        .set(HttpHeaderNames.AUTHORIZATION, "Bearer " + accessToken)
                        .build()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        return HttpResponse.of(future);
    }

    private final class CachedLoginResult {
        @Nullable
        private volatile CacheEntry cacheEntry;

        CachedLoginResult() { }

        CompletableFuture<String> get() {
            final CacheEntry cacheEntry = this.cacheEntry;
            if (cacheEntry != null && !cacheEntry.isExpired()) {
                return UnmodifiableFuture.completedFuture(cacheEntry.loginResult.accessToken);
            }

            return login().thenApply(loginResult -> {
                this.cacheEntry = new CacheEntry(loginResult);
                return loginResult.accessToken;
            });
        }

        private class CacheEntry {
            private final LoginResult loginResult;
            private final long expirationTimeMillis;

            CacheEntry(LoginResult loginResult) {
                this.loginResult = loginResult;
                this.expirationTimeMillis = System.currentTimeMillis() + loginResult.tokenTtl * 1000;
            }

            boolean isExpired() {
                return System.currentTimeMillis() >= expirationTimeMillis;
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class LoginResult {
        private final String accessToken;

        private final Integer tokenTtl;

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
