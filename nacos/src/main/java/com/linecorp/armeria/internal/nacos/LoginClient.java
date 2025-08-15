/*
 * Copyright 2024 LY Corporation
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
package com.linecorp.armeria.internal.nacos;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

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
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.common.util.AsyncLoader;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * A Nacos client that is responsible for
 * <a href="https://nacos.io/en-us/docs/auth.html">Nacos Authentication</a>.
 */
final class LoginClient extends SimpleDecoratingHttpClient {

    static Function<? super HttpClient, LoginClient> newDecorator(WebClient webClient,
                                                                  String username, String password) {
        return delegate -> new LoginClient(delegate, webClient, username, password);
    }

    private final WebClient webClient;
    private final String queryParamsForLogin;

    private final AsyncLoader<LoginResult> tokenLoader =
            AsyncLoader.<LoginResult>builder(cache -> loginInternal())
                       .name("nacos-login")
                       .expireIf(LoginResult::isExpired)
                       .build();

    LoginClient(HttpClient delegate, WebClient webClient, String username, String password) {
        super(delegate);
        this.webClient = requireNonNull(webClient, "webClient");
        queryParamsForLogin = QueryParams.builder()
                                         .add("username", requireNonNull(username, "username"))
                                         .add("password", requireNonNull(password, "password"))
                                         .toQueryString();
    }

    private CompletableFuture<AuthToken> login() {
        return tokenLoader.load().thenApply(loginResult -> loginResult.accessToken);
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
                final HttpRequest newReq = req.mapHeaders(headers -> {
                    return headers.toBuilder()
                                  .set(HttpHeaderNames.AUTHORIZATION, accessToken.asHeaderValue())
                                  .build();
                });
                ctx.updateRequest(newReq);
                return unwrap().execute(ctx, newReq);
            } catch (Exception e) {
                return Exceptions.throwUnsafely(e);
            }
        });

        return HttpResponse.of(future);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class LoginResult {
        private final AuthToken accessToken;

        private final long createdAtNanos;
        private final long tokenTtlNanos;

        @Nullable
        private final Boolean globalAdmin;

        @JsonCreator
        LoginResult(@JsonProperty("accessToken") String accessToken, @JsonProperty("tokenTtl") int tokenTtl,
                    @JsonProperty("globalAdmin") @Nullable Boolean globalAdmin) {
            this.accessToken = AuthToken.ofOAuth2(accessToken);
            createdAtNanos = System.nanoTime();
            tokenTtlNanos = TimeUnit.SECONDS.toNanos(tokenTtl);
            this.globalAdmin = globalAdmin;
        }

        boolean isExpired() {
            final long elapsedNanos = System.nanoTime() - createdAtNanos;
            return elapsedNanos >= tokenTtlNanos;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .omitNullValues()
                              .add("accessToken", accessToken)
                              .add("tokenTtl", TimeUnit.NANOSECONDS.toSeconds(tokenTtlNanos))
                              .add("globalAdmin", globalAdmin)
                              .toString();
        }
    }
}
