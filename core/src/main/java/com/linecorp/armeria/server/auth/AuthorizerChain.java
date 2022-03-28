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
import java.util.concurrent.CompletionStage;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Chains multiple {@link Authorizer}s together into a single {@link Authorizer}.
 * Utilizes {@link AuthorizerSelectionStrategy} to select corresponding {@link AuthFailureHandler}.
 */
final class AuthorizerChain<T> extends AbstractAuthorizerWithHandlers<T> {

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

    private AuthorizerChain(List<? extends Authorizer<T>> authorizers,
                            AuthorizerSelectionStrategy selectionStrategy) {
        requireNonNull(authorizers, "authorizers");
        this.authorizers = ImmutableList.copyOf(authorizers);
        checkArgument(!this.authorizers.isEmpty(), "authorizers are empty");
        this.selectionStrategy = requireNonNull(selectionStrategy, "selectionStrategy");
    }

    AuthorizerChain(Authorizer<T> firstAuthorizer, AuthorizerSelectionStrategy selectionStrategy) {
        this(firstAuthorizer instanceof AuthorizerChain ? ((AuthorizerChain<T>) firstAuthorizer).authorizers
                                                        : ImmutableList.of(firstAuthorizer), selectionStrategy);
    }

    /**
     * Adds a new {@link Authorizer} to the chain.
     * @return an original {@link AuthorizerChain} instance with added {@link Authorizer}.
     */
    @Override
    public Authorizer<T> orElse(Authorizer<T> nextAuthorizer) {
        final ImmutableList.Builder<Authorizer<T>> newAuthorizersBuilder = ImmutableList.builder();
        newAuthorizersBuilder.addAll(authorizers);
        requireNonNull(nextAuthorizer, "nextAuthorizer");
        if (nextAuthorizer instanceof AuthorizerChain) {
            newAuthorizersBuilder.addAll(((AuthorizerChain<T>) nextAuthorizer).authorizers);
        } else {
            newAuthorizersBuilder.add(nextAuthorizer);
        }
        return new AuthorizerChain<T>(newAuthorizersBuilder.build(), selectionStrategy);
    }

    /**
     * Triggers an authorization on the chain of {@link Authorizer}s.
     * @return a {@link CompletionStage} that will resolve to {@code true} if any of {@link Authorizer}s in the
     *         chain authorize the request, or {@code false} if none of {@link Authorizer}s in the chain
     *         authorize the request. If the future resolves exceptionally, the request will not be authorized.
     */
    @Override
    public CompletionStage<AuthorizationStatus> authorizeAndSupplyHandlers(ServiceRequestContext ctx,
                                                                           @Nullable T data) {
        return authorizeAndSupplyHandlers(authorizers.iterator(), true, null, ctx, data);
    }

    private CompletionStage<AuthorizationStatus> authorizeAndSupplyHandlers(
            Iterator<? extends Authorizer<T>> iterator,
            boolean first, @Nullable AuthFailureHandler prevFailureHandler,
            ServiceRequestContext ctx, @Nullable T data) {
        final Authorizer<T> nextAuthorizer = iterator.next();
        return AuthorizerUtil.authorizeAndSupplyHandlers(nextAuthorizer, ctx, data).thenComposeAsync(result -> {
            if (result == null) {
                throw AuthorizerUtil.newNullResultException(nextAuthorizer);
            } else {
                if (result.isAuthorized()) {
                    // always return associated successHandler on success!
                    // this could be NULL
                    return UnmodifiableFuture.completedFuture(
                            AuthorizationStatus.ofSuccess(result.successHandler()));
                }
                // handle failure result
                final AuthFailureHandler failureHandler;
                final AuthFailureHandler nextFailureHandler = result.failureHandler();
                switch (selectionStrategy) {
                    case FIRST:
                        // (re-)assign the handler only for the FIRST element in the chain
                        if (first) {
                            failureHandler = nextFailureHandler;
                        } else { // passthrough the handler form the previous item
                            failureHandler = prevFailureHandler;
                        }
                        break;
                    case FIRST_WITH_HANDLER:
                        // set failureHandler only if it's not yet set
                        if (prevFailureHandler == null && nextFailureHandler != null) {
                            failureHandler = nextFailureHandler;
                        } else { // passthrough the handler form the previous item
                            failureHandler = prevFailureHandler;
                        }
                        break;
                    case LAST_WITH_HANDLER:
                        // reset failureHandler on any failure
                        if (nextFailureHandler != null) {
                            failureHandler = nextFailureHandler;
                        } else { // passthrough the handler form the previous item
                            failureHandler = prevFailureHandler;
                        }
                        break;
                    default:
                        // passthrough the handler form the previous item
                        failureHandler = prevFailureHandler;
                        break;
                }
                if (!iterator.hasNext()) {
                    // this is the last item in the chain
                    return UnmodifiableFuture.completedFuture(
                            AuthorizationStatus.ofFailure(
                                    (selectionStrategy == AuthorizerSelectionStrategy.LAST) ? nextFailureHandler
                                                                                            : failureHandler));
                }
                // continue to the next...
                return authorizeAndSupplyHandlers(iterator, false, failureHandler, ctx, data);
            }
        }, ctx.eventLoop());
    }

    @Override
    public String toString() {
        return authorizers.toString();
    }
}
