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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.auth.oauth2.AccessTokenCapsule;
import com.linecorp.armeria.common.auth.oauth2.RefreshAccessTokenRequest;
import com.linecorp.armeria.common.auth.oauth2.TokenRequestException;

/**
 * Base implementation of OAuth 2.0 Access Token Grant flow to obtain Access Token.
 * Implements Access Token loading, storing and refreshing.
 */
abstract class AbstractOAuth2AuthorizationGrant implements OAuth2AuthorizationGrant {

    /**
     * A period when the token should be refreshed proactively prior to its expiry.
     */
    private static final Duration DEFAULT_REFRESH_BEFORE = Duration.ofMinutes(1L); // 1 minute

    private final AtomicReference<AccessTokenCapsule> tokenRef;

    private final RefreshAccessTokenRequest refreshRequest;

    private final Duration refreshBefore;

    @Nullable
    private final Supplier<AccessTokenCapsule> tokenSupplier;
    @Nullable
    private final Consumer<AccessTokenCapsule> tokenConsumer;

    AbstractOAuth2AuthorizationGrant(RefreshAccessTokenRequest refreshRequest, @Nullable Duration refreshBefore,
                                     @Nullable Supplier<AccessTokenCapsule> tokenSupplier,
                                     @Nullable Consumer<AccessTokenCapsule> tokenConsumer) {
        tokenRef = new AtomicReference<>();
        this.refreshRequest = requireNonNull(refreshRequest, "refreshRequest");
        this.refreshBefore = refreshBefore == null ? DEFAULT_REFRESH_BEFORE : refreshBefore;
        this.tokenSupplier = tokenSupplier;
        this.tokenConsumer = tokenConsumer;
    }

    protected abstract CompletableFuture<AccessTokenCapsule> obtainAccessToken(
            @Nullable AccessTokenCapsule token);

    private CompletableFuture<AccessTokenCapsule> obtainAccessTokenExclusively(
            @Nullable AccessTokenCapsule token) {
        return obtainAccessToken(token).thenApply(t -> {
            tokenRef.set(t); // reset the token reference
            if (tokenConsumer != null) {
                tokenConsumer.accept(t); // store token to an optional storage (e.g. secret store)
            }
            return t;
        });
    }

    private CompletableFuture<AccessTokenCapsule> refreshAccessToken(AccessTokenCapsule token) {
        if (token.refreshToken() != null) {
            // try refreshing token if refresh token was previously provided
            try {
                return refreshRequest.make(token);
            } catch (TokenRequestException e) {
                // token refresh request failed
                // try to re-obtain access token
                return obtainAccessToken(token);
            }
        }
        // try to re-obtain access token
        return obtainAccessToken(token);
    }

    private CompletableFuture<AccessTokenCapsule> getOrRefreshAccessToken(AccessTokenCapsule token,
                                                                          boolean reset,
                                                                          boolean lock) {
        // check if it's still valid
        final Instant instant = Instant.now().plus(refreshBefore);
        if (token.isValid(instant)) {
            // simply return a valid token
            if (reset) {
                return CompletableFuture.completedFuture(token).thenApply(t -> {
                    tokenRef.set(t); // reset the token reference
                    return t;
                });
            } else {
                return CompletableFuture.completedFuture(token);
            }
        } else {
            if (lock) {
                synchronized (tokenRef) {
                    // refresh token exclusively
                    return refreshAccessTokenExclusively(instant);
                }
            } else {
                // refresh token exclusively
                return refreshAccessTokenExclusively(instant);
            }
        }
    }

    private CompletableFuture<AccessTokenCapsule> refreshAccessTokenExclusively(Instant instant) {
        // after acquiring the lock, re-check if it's a valid token
        final AccessTokenCapsule token = tokenRef.get();
        if (token.isValid(instant)) {
            // simply return a valid token
            return CompletableFuture.completedFuture(token);
        }
        // otherwise, refresh it
        return refreshAccessToken(token).thenApply(t -> {
            tokenRef.set(t); // reset the token reference
            if (tokenConsumer != null) {
                tokenConsumer.accept(t); // store token to an optional storage (e.g. secret store)
            }
            return t;
        });
    }

    @Override
    public CompletionStage<AccessTokenCapsule> getAccessToken() {
        AccessTokenCapsule token = tokenRef.get();
        if (token != null) {
            // token already present
            return getOrRefreshAccessToken(token, false, true);
        }
        // token is not yet present
        // lock and obtain token exclusively
        synchronized (tokenRef) {
            // re-check if the token already present
            token = tokenRef.get();
            if (token != null) {
                // token already present
                return getOrRefreshAccessToken(token, false, false);
            }
            // token not yet present
            // try loading access token
            if (tokenSupplier != null) {
                token = tokenSupplier.get();
            }
            if (token != null) {
                // token loaded
                return getOrRefreshAccessToken(token, true, false);
            }
            return obtainAccessTokenExclusively(null);
        }
    }
}
