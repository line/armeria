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

package com.linecorp.armeria.server.auth;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Chains multiple {@link Authorizer}s together into a single {@link Authorizer}.
 * Utilizes {@link AuthorizerSelectionStrategy} to select corresponding {@link AuthFailureHandler}.
 */
final class AuthorizerChain<T> implements Authorizer<T> {

    enum AuthorizerSelectionStrategy {
        /**
         * Select a handler provided by the first {@link Authorizer} in the chain.
         */
        FIRST,
        /**
         * Select a handler provided by the last {@link Authorizer} in the chain.
         */
        LAST,
        /**
         * Select a handler provided by the first {@link Authorizer} in the chain that has an associated
         * handler actually defined (non-NULL).
         */
        FIRST_WITH_HANDLER,
        /**
         * Select a handler provided by the last {@link Authorizer} in the chain that has an associated
         * handler actually defined (non-NULL).
         */
        LAST_WITH_HANDLER
    }

    private final List<? extends Authorizer<T>> authorizers;
    private final AuthorizerSelectionStrategy selectionStrategy;
    @Nullable
    private AuthSuccessHandler successHandler;
    @Nullable
    private AuthFailureHandler failureHandler;

    AuthorizerChain(Iterable<? extends Authorizer<T>> authorizers,
                    AuthorizerSelectionStrategy selectionStrategy) {
        requireNonNull(authorizers, "authorizers");
        this.authorizers = ImmutableList.copyOf(authorizers);
        checkArgument(!this.authorizers.isEmpty(), "authorizers are empty");
        final Authorizer<T> firstAuthorizer = this.authorizers.get(0);
        this.selectionStrategy = requireNonNull(selectionStrategy, "selectionStrategy");
        if (selectionStrategy == AuthorizerSelectionStrategy.FIRST) {
            // this could be NULL
            failureHandler = firstAuthorizer.failureHandler();
        }
    }

    /**
     * Adds a new {@link Authorizer} to the chain.
     * @return an original {@link AuthorizerChain} instance with added {@link Authorizer}.
     */
    @Override
    public Authorizer<T> orElse(Authorizer<T> nextAuthorizer) {
        final ImmutableList.Builder<Authorizer<T>> newAuthorizersBuilder = ImmutableList.builder();
        newAuthorizersBuilder.addAll(authorizers).add(requireNonNull(nextAuthorizer, "nextAuthorizer"));
        return new AuthorizerChain<>(newAuthorizersBuilder.build(), selectionStrategy);
    }

    /**
     * Triggers an authorization on the chain of {@link Authorizer}s.
     * @return a {@link CompletionStage} that will resolve to {@code true} if any of {@link Authorizer}s in the
     *         chain authorize the request, or {@code false} if none of {@link Authorizer}s in the chain
     *         authorize the request. If the future resolves exceptionally, the request will not be authorized.
     */
    @Override
    public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, T data) {
        return authorize(authorizers.iterator(), ctx, data);
    }

    private CompletionStage<Boolean> authorize(Iterator<? extends Authorizer<T>> iterator,
                                               ServiceRequestContext ctx, T data) {
        final Authorizer<T> nextAuthorizer = iterator.next();
        return AuthorizerUtil.authorize(nextAuthorizer, ctx, data).thenComposeAsync(result -> {
            if (result == null) {
                throw AuthorizerUtil.newNullResultException(nextAuthorizer);
            } else {
                if (result) {
                    // always reset successHandler on success!
                    // this could be NULL
                    successHandler = nextAuthorizer.successHandler();
                    return CompletableFuture.completedFuture(true);
                }
                // handle failure result
                final AuthFailureHandler nextFailureHandler = nextAuthorizer.failureHandler();
                switch (selectionStrategy) {
                    case FIRST_WITH_HANDLER:
                        // set failureHandler only if it's not yet set
                        if (failureHandler == null && nextFailureHandler != null) {
                            failureHandler = nextFailureHandler;
                        }
                        break;
                    case LAST_WITH_HANDLER:
                        // reset failureHandler on any failure
                        if (nextFailureHandler != null) {
                            failureHandler = nextFailureHandler;
                        }
                        break;
                }
                if (!iterator.hasNext()) {
                    if (selectionStrategy == AuthorizerSelectionStrategy.LAST) {
                        // this could be NULL
                        failureHandler = nextFailureHandler;
                    }
                    return CompletableFuture.completedFuture(false);
                }
                // continue to the next...
                return authorize(iterator, ctx, data);
            }
        }, ctx.eventLoop());
    }

    /**
     * Returns the {@link AuthSuccessHandler} which handles successfully authorized requests.
     * This will always match the {@link Authorizer} that succeeded.
     * <p>
     * CAUTION: This method has to be called after
     * {@link AuthorizerChain#authorize(ServiceRequestContext, Object)} executed to produce correct result.
     * </p>
     * @return An instance of {@link AuthSuccessHandler} associated with the {@link Authorizer} that succeeded
     *         to handle successfully authorized requests or {@code null} to use the default.
     */
    @Nullable
    @Override
    public AuthSuccessHandler successHandler() {
        return successHandler;
    }

    /**
     * Returns the {@link AuthFailureHandler} which handles the requests with failed authorization.
     * The result of this method will depend on {@link AuthorizerSelectionStrategy} configured.
     * <ul>
     *     <li>{@link AuthorizerSelectionStrategy#FIRST} will always return {@link AuthFailureHandler}
     *     associated with the first {@link Authorizer} in the chain.</li>
     *     <li>{@link AuthorizerSelectionStrategy#LAST} will always return {@link AuthFailureHandler}
     *     associated with the last {@link Authorizer} in the chain.</li>
     *     <li>{@link AuthorizerSelectionStrategy#FIRST_WITH_HANDLER} will return first non-null
     *     {@link AuthFailureHandler} associated with an executed {@link Authorizer} in the chain.</li>
     *     <li>{@link AuthorizerSelectionStrategy#LAST_WITH_HANDLER} will return last non-null
     *     {@link AuthFailureHandler} associated with an executed {@link Authorizer} in the chain.</li>
     * </ul>
     * <p>
     * CAUTION: This method has to be called after
     * {@link AuthorizerChain#authorize(ServiceRequestContext, Object)} executed to produce correct result.
     * </p>
     * @return An instance of {@link AuthFailureHandler} to handle the requests with failed authorization
     *         or {@code null} to use the default.
     */
    @Nullable
    @Override
    public AuthFailureHandler failureHandler() {
        return failureHandler;
    }

    @Override
    public String toString() {
        return authorizers.toString();
    }
}
