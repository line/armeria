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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.auth.oauth2.GrantedOAuth2AccessToken;
import com.linecorp.armeria.common.auth.oauth2.InvalidClientException;
import com.linecorp.armeria.common.auth.oauth2.TokenRequestException;
import com.linecorp.armeria.common.auth.oauth2.UnsupportedMediaTypeException;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * Base implementation of OAuth 2.0 Access Token Grant flow to obtain Access Token.
 * Implements Access Token loading, storing and refreshing.
 */
abstract class AbstractOAuth2AuthorizationGrant implements OAuth2AuthorizationGrant {

    /**
     * Holds a reference to the granted access token.
     */
    private final AtomicReference<GrantedOAuth2AccessToken> tokenRef;

    /**
     * Executes obtain and refresh token operations serially on a separate thread.
     */
    private final ExecutorService serialExecutor;

    private final RefreshAccessTokenRequest refreshRequest;

    private final Duration refreshBefore;

    @Nullable
    private final Supplier<? extends GrantedOAuth2AccessToken> tokenSupplier;
    @Nullable
    private final Consumer<? super GrantedOAuth2AccessToken> tokenConsumer;

    AbstractOAuth2AuthorizationGrant(RefreshAccessTokenRequest refreshRequest, Duration refreshBefore,
                                     @Nullable Supplier<? extends GrantedOAuth2AccessToken> tokenSupplier,
                                     @Nullable Consumer<? super GrantedOAuth2AccessToken> tokenConsumer) {
        tokenRef = new AtomicReference<>();
        serialExecutor = Executors.newSingleThreadExecutor();
        this.refreshRequest = requireNonNull(refreshRequest, "refreshRequest");
        this.refreshBefore = requireNonNull(refreshBefore, "refreshBefore");
        this.tokenSupplier = tokenSupplier;
        this.tokenConsumer = tokenConsumer;
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
    protected abstract CompletableFuture<GrantedOAuth2AccessToken> obtainAccessTokenAsync(
            @Nullable GrantedOAuth2AccessToken token);

    /**
     * Obtains a new access token from the token end-point.
     * Optionally stores access token to registered {@link Consumer} for longer term storage.
     * @return an {@link GrantedOAuth2AccessToken} that contains requested access token.
     * @throws TokenRequestException when the endpoint returns {code HTTP 400 (Bad Request)} status and the
     *                               response payload contains the details of the error.
     * @throws InvalidClientException when the endpoint returns {@code HTTP 401 (Unauthorized)} status, which
     *                                typically indicates that client authentication failed (e.g.: unknown
     *                                client, no client authentication included, or unsupported authentication
     *                                method).
     * @throws UnsupportedMediaTypeException if the media type of the response does not match the expected
     *                                       (JSON).
     */
    private GrantedOAuth2AccessToken obtainAccessToken() {
        final GrantedOAuth2AccessToken token = obtainAccessTokenAsync(null).join();
        tokenRef.set(token); // reset the token reference
        if (tokenConsumer != null) {
            tokenConsumer.accept(token); // store token to an optional storage (e.g. secret store)
        }
        return token;
    }

    /**
     * Refreshes access token using refresh token provided with the previous access token response
     * asynchronously, otherwise, if no refresh token available, re-obtains a new access token from the token
     * end-point.
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
    private CompletableFuture<GrantedOAuth2AccessToken> refreshAccessTokenAsync(
            GrantedOAuth2AccessToken token) {
        if (token.refreshToken() != null) {
            // try refreshing token if refresh token was previously provided
            return refreshRequest.make(token);
        }
        // try to re-obtain access token
        return obtainAccessTokenAsync(token);
    }

    /**
     * Refreshes access token using refresh token provided with the previous access token response, otherwise,
     * if no refresh token available, re-obtains a new access token from the token end-point.
     * If the refresh token request fails with {@link TokenRequestException}, tries to re-obtains a new access
     * token from the token end-point.
     * Optionally stores access token to registered {@link Consumer} for longer term storage.
     * @return an {@link GrantedOAuth2AccessToken} that contains requested access token.
     * @throws TokenRequestException when the endpoint returns {code HTTP 400 (Bad Request)} status and the
     *                               response payload contains the details of the error.
     * @throws InvalidClientException when the endpoint returns {@code HTTP 401 (Unauthorized)} status, which
     *                                typically indicates that client authentication failed (e.g.: unknown
     *                                client, no client authentication included, or unsupported authentication
     *                                method).
     * @throws UnsupportedMediaTypeException if the media type of the response does not match the expected
     *                                       (JSON).
     */
    private GrantedOAuth2AccessToken refreshAccessToken(Instant instant) {
        // after acquiring the lock, re-check if it's a valid token
        final GrantedOAuth2AccessToken token = tokenRef.get();
        if (token.isValid(instant)) {
            // simply return a valid token
            return token;
        }
        // otherwise, refresh it
        GrantedOAuth2AccessToken refreshedToken;
        try {
            refreshedToken = refreshAccessTokenAsync(token).join();
        } catch (CompletionException e) {
            if (Exceptions.peel(e) instanceof TokenRequestException) {
                // token refresh failed, try to re-obtain access token instead
                refreshedToken = obtainAccessToken();
            } else {
                throw e;
            }
        }
        tokenRef.set(refreshedToken); // reset the token reference
        if (tokenConsumer != null) {
            tokenConsumer.accept(refreshedToken); // store token to an optional storage (e.g. secret store)
        }
        return refreshedToken;
    }

    /**
     * Validates access token and refreshes it asynchronously if the token has expired or about to expire.
     * Refreshing of the token facilitated by a dedicated single-thread {@link ExecutorService} which makes sure
     * all token obtain and refresh requests executed serially.
     */
    private CompletableFuture<GrantedOAuth2AccessToken> validateOrRefreshAccessTokenAsync(
            GrantedOAuth2AccessToken token,
            boolean reset) {
        // check if it's still valid
        final Instant instant = Instant.now().plus(refreshBefore);
        if (token.isValid(instant)) {
            // simply return a valid token
            if (reset) {
                tokenRef.set(token); // reset the token reference
            }
            return CompletableFuture.completedFuture(token);
        } else {
            // try to refresh token serially using single-thread executor
            return CompletableFuture.supplyAsync(() -> {
                // refresh token exclusively
                return refreshAccessToken(instant);
            }, serialExecutor);
        }
    }

    /**
     * Validates access token and refreshes it if the token has expired or about to expire.
     */
    private GrantedOAuth2AccessToken validateOrRefreshAccessToken(GrantedOAuth2AccessToken token,
                                                                  boolean reset) {
        // check if it's still valid
        final Instant instant = Instant.now().plus(refreshBefore);
        if (token.isValid(instant)) {
            // simply return a valid token
            if (reset) {
                tokenRef.set(token); // reset the token reference
            }
            return token;
        } else {
            // refresh token exclusively
            return refreshAccessToken(instant);
        }
    }

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
        final GrantedOAuth2AccessToken token1 = tokenRef.get();
        if (token1 != null) {
            // token already present
            return validateOrRefreshAccessTokenAsync(token1, false);
        }

        // token is not yet present
        // try to obtain token serially using single-thread executor
        return CompletableFuture.supplyAsync(() -> {
            // re-check if the token already present
            GrantedOAuth2AccessToken token2 = tokenRef.get();
            if (token2 != null) {
                // token already present
                return validateOrRefreshAccessToken(token2, false);
            }

            // token not yet present
            // try loading access token
            if (tokenSupplier != null) {
                token2 = tokenSupplier.get();
            }
            if (token2 != null) {
                // token loaded
                return validateOrRefreshAccessToken(token2, true);
            }

            return obtainAccessToken();
        }, serialExecutor);
    }

    @Override
    public void close() {
        serialExecutor.shutdown();
    }
}
