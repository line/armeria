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
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

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

    private final RefreshAccessTokenRequest refreshRequest;

    private final Duration refreshBefore;

    /**
     * Holds a token object and facilitates its lifecycle.
     */
    private final TokenLifecycleManager<GrantedOAuth2AccessToken> tokenManager;

    AbstractOAuth2AuthorizationGrant(RefreshAccessTokenRequest refreshRequest, Duration refreshBefore,
                                     @Nullable Supplier<? extends GrantedOAuth2AccessToken> tokenSupplier,
                                     @Nullable Consumer<? super GrantedOAuth2AccessToken> tokenConsumer,
                                     @Nullable Executor executor) {
        this.refreshRequest = requireNonNull(refreshRequest, "refreshRequest");
        this.refreshBefore = requireNonNull(refreshBefore, "refreshBefore");
        tokenManager = new TokenLifecycleManager<>(this::isValid, this::canRefresh,
                                                   this::shallObtainInsteadOfUpdate,
                                                   this::obtainAccessToken, this::refreshAccessToken,
                                                   tokenSupplier, tokenConsumer, executor);
    }

    /**
     * Tests the token for validity at the given {@link Instant} time.
     */
    private boolean isValid(GrantedOAuth2AccessToken token, Instant now) {
        return token.isValid(now.plus(refreshBefore));
    }

    /**
     * Tests whether the token object can be refreshed or re-obtained.
     */
    private boolean canRefresh(GrantedOAuth2AccessToken token) {
        return token.refreshToken() != null;
    }

    /**
     * Tests whether given {@link Throwable} indicates that the token shall be re-obtained
     * after the refresh operation failure.
     */
    private boolean shallObtainInsteadOfUpdate(Throwable throwable) {
        return throwable instanceof TokenRequestException;
    }

    /**
     * Refreshes access token.
     */
    private CompletionStage<GrantedOAuth2AccessToken> refreshAccessToken(GrantedOAuth2AccessToken token) {
        return refreshRequest.make(token);
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
        return tokenManager.get();
    }
}
