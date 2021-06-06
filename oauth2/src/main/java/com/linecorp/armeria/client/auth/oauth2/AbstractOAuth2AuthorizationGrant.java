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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.common.auth.oauth2.GrantedOAuth2AccessToken;
import com.linecorp.armeria.common.auth.oauth2.InvalidClientException;
import com.linecorp.armeria.common.auth.oauth2.TokenRequestException;
import com.linecorp.armeria.common.auth.oauth2.UnsupportedMediaTypeException;
import com.linecorp.armeria.internal.client.auth.oauth2.RefreshAccessTokenRequest;

import io.netty.util.concurrent.ScheduledFuture;

/**
 * Base implementation of OAuth 2.0 Access Token Grant flow to obtain Access Token.
 * Implements Access Token loading, storing and refreshing.
 */
abstract class AbstractOAuth2AuthorizationGrant implements OAuth2AuthorizationGrant {

    private static final int MAX_TOTAL_ATTEMPTS = 10;

    private static final AtomicIntegerFieldUpdater<AbstractOAuth2AuthorizationGrant> authenticatingUpdater =
            AtomicIntegerFieldUpdater.newUpdater(AbstractOAuth2AuthorizationGrant.class, "authenticating");

    private final RefreshAccessTokenRequest refreshRequest;
    private final Duration refreshBefore;

    @Nullable
    private volatile GrantedOAuth2AccessToken accessToken;
    private volatile int authenticating;

    AbstractOAuth2AuthorizationGrant(RefreshAccessTokenRequest refreshRequest, Duration refreshBefore) {
        this.refreshRequest = requireNonNull(refreshRequest, "refreshRequest");
        this.refreshBefore = requireNonNull(refreshBefore, "refreshBefore");
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
    public final CompletionStage<GrantedOAuth2AccessToken> getAccessToken(ClientRequestContext ctx) {
        final CompletableFuture<GrantedOAuth2AccessToken> future = new CompletableFuture<>();
        doGetAccessToken(ctx, future, 1);
        return future;
    }

    private void doGetAccessToken(ClientRequestContext ctx,
                                  CompletableFuture<GrantedOAuth2AccessToken> future, int attempts) {
        final GrantedOAuth2AccessToken accessToken = this.accessToken;

        if (isValidToken(accessToken)) {
            future.complete(accessToken);
            return;
        }
        if (!authenticatingUpdater.compareAndSet(this, 0, 1)) {
            scheduleNextRetry(ctx, future, attempts);
            return;
        }

        final GrantedOAuth2AccessToken currentToken = this.accessToken;

        // check token's validity again since it may have been updated.
        if (isValidToken(currentToken)) {
            authenticatingUpdater.set(this, 0);
            future.complete(currentToken);
            return;
        }
        if (currentToken != null && currentToken.isRefreshable()) {
            refreshAccessToken(currentToken).handle((newToken, cause) -> {
                if (cause != null) {
                    if (cause instanceof TokenRequestException) {
                        // retry after setting accessToken null
                        // so that it tries to issue a new token from scratch.
                        this.accessToken = null;
                        authenticatingUpdater.set(this, 0);
                        scheduleNextRetry(ctx, future, attempts);
                        return null;
                    }
                    authenticatingUpdater.set(this, 0);
                    future.completeExceptionally(cause);
                    return null;
                }
                this.accessToken = newToken;
                authenticatingUpdater.set(this, 0);
                future.complete(newToken);
                return null;
            });
            return;
        }
        obtainAccessToken(currentToken).handle((newToken, cause) -> {
            if (cause != null) {
                authenticatingUpdater.set(this, 0);
                future.completeExceptionally(cause);
                return null;
            }
            this.accessToken = newToken;
            authenticatingUpdater.set(this, 0);
            future.complete(newToken);
            return null;
        });
    }

    private boolean isValidToken(@Nullable GrantedOAuth2AccessToken token) {
        return token != null && token.isValid(Instant.now().plus(refreshBefore));
    }

    private CompletionStage<GrantedOAuth2AccessToken> refreshAccessToken(GrantedOAuth2AccessToken token) {
        return refreshRequest.make(token);
    }

    private void scheduleNextRetry(ClientRequestContext ctx,
                                   CompletableFuture<GrantedOAuth2AccessToken> future, int attempts) {
        if (attempts > MAX_TOTAL_ATTEMPTS) {
            // TODO(ks-yim): throw a more specific exception
            future.completeExceptionally(new TimeoutException());
            return;
        }
        @SuppressWarnings("unchecked")
        final ScheduledFuture<Void> scheduledFuture =
                (ScheduledFuture<Void>) ctx.eventLoop().schedule(
                        () -> doGetAccessToken(ctx, future, attempts + 1),
                        getNextDelay(attempts), TimeUnit.MILLISECONDS);
        scheduledFuture.addListener(scheduled -> {
            if (scheduled.isCancelled()) {
                // future is cancelled when the client factory is closed.
                future.completeExceptionally(new IllegalStateException(
                        ClientFactory.class.getSimpleName() + " has been closed."));
            } else if (scheduled.cause() != null) {
                future.completeExceptionally(scheduled.cause());
            }
        });
    }

    private static long getNextDelay(int numAttemptsSoFar) {
        // TODO(ks-yim): replace this with exponential + jitter delay.
        return ThreadLocalRandom.current().nextLong(70L);
    }
}
