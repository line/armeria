/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.client.auth.oauth2;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.auth.oauth2.GrantedOAuth2AccessToken;
import com.linecorp.armeria.common.auth.oauth2.InvalidClientException;
import com.linecorp.armeria.common.auth.oauth2.TokenRequestException;
import com.linecorp.armeria.common.auth.oauth2.UnsupportedMediaTypeException;
import com.linecorp.armeria.internal.client.auth.oauth2.RefreshAccessTokenRequest;

/**
 * Base implementation of OAuth 2.0 Access Token Grant flow to obtain Access Token.
 * Implements Access Token loading, storing and refreshing.
 */
abstract class AbstractOAuth2AuthorizationGrant implements OAuth2AuthorizationGrant {

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<
            AbstractOAuth2AuthorizationGrant, CompletableFuture> tokenFutureUpdater =
            AtomicReferenceFieldUpdater.newUpdater(
                    AbstractOAuth2AuthorizationGrant.class, CompletableFuture.class, "tokenFuture");

    private final RefreshAccessTokenRequest refreshRequest;

    private final Duration refreshBefore;

    @Nullable
    private final Supplier<CompletableFuture<? extends GrantedOAuth2AccessToken>> fallbackTokenProvider;

    @Nullable
    private final Consumer<? super GrantedOAuth2AccessToken> newTokenConsumer;

    private volatile CompletableFuture<GrantedOAuth2AccessToken> tokenFuture =
            CompletableFuture.completedFuture(null);

    AbstractOAuth2AuthorizationGrant(
            RefreshAccessTokenRequest refreshRequest, Duration refreshBefore,
            @Nullable Supplier<CompletableFuture<? extends GrantedOAuth2AccessToken>> fallbackTokenProvider,
            @Nullable Consumer<? super GrantedOAuth2AccessToken> newTokenConsumer) {
        this.refreshRequest = requireNonNull(refreshRequest, "refreshRequest");
        this.refreshBefore = requireNonNull(refreshBefore, "refreshBefore");
        this.fallbackTokenProvider = fallbackTokenProvider;
        this.newTokenConsumer = newTokenConsumer;
    }

    /**
     * Obtains a new access token from the token end-point asynchronously.
     * @return A {@link CompletableFuture} carrying the requested {@link GrantedOAuth2AccessToken} or an
     *         exception, if the request failed.
     * @throws TokenRequestException when the endpoint returns {code HTTP 400 (Bad Request)} status and the
     *                               response payload contains the details of the error.
     * @throws InvalidClientException when the endpoint returns {@code HTTP 401 (Unauthorized)} status, which
     *                                typically indicates that client authentication failed (e.g.: unknown
     *                                client, no client authentication included, or unsupported authentication
     *                                method).
     * @throws UnsupportedMediaTypeException if the media type of the response does not match the expected
     *                                       (JSON).
     */
    abstract CompletableFuture<GrantedOAuth2AccessToken> obtainAccessToken(
            @Nullable GrantedOAuth2AccessToken token);

    /**
     * Produces valid OAuth 2.0 Access Token.
     * Returns cached access token if previously obtained from the token end-point.
     * Optionally loads access token from longer term storage provided by registered {@link Supplier}.
     * If access token has not previously obtained, obtains is from the OAuth 2.0 token end-point using
     * dedicated single-thread {@link ExecutorService} which makes sure all token obtain and refresh requests
     * executed serially.
     * Validates access token and refreshes it if necessary.
     */
    @Override
    public CompletionStage<GrantedOAuth2AccessToken> getAccessToken() {
        CompletableFuture<GrantedOAuth2AccessToken> future;
        GrantedOAuth2AccessToken token = null;
        for (;;) {
            final CompletableFuture<GrantedOAuth2AccessToken> tokenFuture = this.tokenFuture;
            if (!tokenFuture.isDone()) {
                return tokenFuture;
            }
            if (!tokenFuture.isCompletedExceptionally()) {
                token = tokenFuture.join();
                if (isValidToken(token)) {
                    return tokenFuture;
                }
            }

            // `tokenFuture` got completed with an invalid token; try again.
            future = new CompletableFuture<>();
            if (tokenFutureUpdater.compareAndSet(this, tokenFuture, future)) {
                break;
            }
        }

        final CompletableFuture<GrantedOAuth2AccessToken> newTokenFuture = future;
        if (token == null && fallbackTokenProvider != null) {
            fallbackTokenProvider.get().handle((storedToken, unused) -> {
                if (isValidToken(storedToken)) {
                    newTokenFuture.complete(storedToken);
                    return null;
                }
                issueAccessToken(null, newTokenFuture);
                return null;
            });
            return newTokenFuture;
        }
        if (token != null && token.isRefreshable()) {
            refreshAccessToken(token, newTokenFuture);
            return newTokenFuture;
        }
        issueAccessToken(token, newTokenFuture);
        return newTokenFuture;
    }

    private boolean isValidToken(@Nullable GrantedOAuth2AccessToken token) {
        return token != null && token.isValid(Instant.now().plus(refreshBefore));
    }

    private void issueAccessToken(@Nullable GrantedOAuth2AccessToken token,
                                  CompletableFuture<GrantedOAuth2AccessToken> future) {
        obtainAccessToken(token).handle((newToken, cause) -> {
            if (cause != null) {
                future.completeExceptionally(cause);
            } else {
                if (newTokenConsumer != null) {
                    newTokenConsumer.accept(newToken);
                }
                future.complete(newToken);
            }
            return null;
        });
    }

    @VisibleForTesting
    void refreshAccessToken(GrantedOAuth2AccessToken token,
                            CompletableFuture<GrantedOAuth2AccessToken> future) {
        refreshRequest.make(token).handle((newToken, cause) -> {
            if (cause instanceof TokenRequestException) {
                issueAccessToken(token, future);
                return null;
            }
            if (cause != null) {
                future.completeExceptionally(cause);
                return null;
            }
            if (newTokenConsumer != null) {
                newTokenConsumer.accept(newToken);
            }
            future.complete(newToken);
            return null;
        });
    }
}
