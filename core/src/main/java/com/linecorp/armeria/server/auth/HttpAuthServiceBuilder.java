/*
 * Copyright 2016 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.google.common.collect.Lists;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Service;

import io.netty.util.AsciiString;

/**
 * Builds a new {@link HttpAuthService}.
 */
public final class HttpAuthServiceBuilder {

    private final List<Authorizer<HttpRequest>> authorizers = new ArrayList<>();

    /**
     * Adds an {@link Authorizer}.
     */
    public HttpAuthServiceBuilder add(Authorizer<HttpRequest> authorizer) {
        authorizers.add(requireNonNull(authorizer, "authorizer"));
        return this;
    }

    /**
     * Adds multiple {@link Authorizer}s.
     */
    public HttpAuthServiceBuilder add(Iterable<? extends Authorizer<HttpRequest>> authorizers) {
        this.authorizers.addAll(Lists.newArrayList(requireNonNull(authorizers, "authorizers")));
        return this;
    }

    /**
     * Adds an HTTP basic {@link Authorizer}.
     */
    public HttpAuthServiceBuilder addBasicAuth(Authorizer<? super BasicToken> authorizer) {
        return addTokenAuthorizer(AuthTokenExtractors.BASIC,
                                  requireNonNull(authorizer, "authorizer"));
    }

    /**
     * Adds an HTTP basic {@link Authorizer} for the given {@code header}.
     */
    public HttpAuthServiceBuilder addBasicAuth(Authorizer<? super BasicToken> authorizer, AsciiString header) {
        return addTokenAuthorizer(new BasicTokenExtractor(requireNonNull(header, "header")),
                                  requireNonNull(authorizer, "authorizer"));
    }

    /**
     * Adds an OAuth1a {@link Authorizer}.
     */
    public HttpAuthServiceBuilder addOAuth1a(Authorizer<? super OAuth1aToken> authorizer) {
        return addTokenAuthorizer(AuthTokenExtractors.OAUTH1A,
                                  requireNonNull(authorizer, "authorizer"));
    }

    /**
     * Adds an OAuth1a {@link Authorizer} for the given {@code header}.
     */
    public HttpAuthServiceBuilder addOAuth1a(Authorizer<? super OAuth1aToken> authorizer, AsciiString header) {
        return addTokenAuthorizer(new OAuth1aTokenExtractor(requireNonNull(header, "header")),
                                  requireNonNull(authorizer, "authorizer"));
    }

    /**
     * Adds an OAuth2 {@link Authorizer}.
     */
    public HttpAuthServiceBuilder addOAuth2(Authorizer<? super OAuth2Token> authorizer) {
        return addTokenAuthorizer(AuthTokenExtractors.OAUTH2, requireNonNull(authorizer, "authorizer"));
    }

    /**
     * Adds an OAuth2 {@link Authorizer} for the given {@code header}.
     */
    public HttpAuthServiceBuilder addOAuth2(Authorizer<? super OAuth2Token> authorizer, AsciiString header) {
        return addTokenAuthorizer(new OAuth2TokenExtractor(requireNonNull(header, "header")),
                                  requireNonNull(authorizer, "authorizer"));
    }

    /**
     * Adds a token-based {@link Authorizer}.
     */
    public <T> HttpAuthServiceBuilder addTokenAuthorizer(
            Function<HttpHeaders, T> tokenExtractor, Authorizer<? super T> authorizer) {
        requireNonNull(tokenExtractor, "tokenExtractor");
        requireNonNull(authorizer, "authorizer");
        final Authorizer<HttpRequest> requestAuthorizer = (ctx, req) -> {
            T token = tokenExtractor.apply(req.headers());
            if (token == null) {
                return CompletableFuture.completedFuture(false);
            }
            return authorizer.authorize(ctx, token);
        };
        authorizers.add(requestAuthorizer);
        return this;
    }

    /**
     * Returns a newly-created {@link HttpAuthService} based on the {@link Authorizer}s added to this builder.
     */
    public HttpAuthService build(Service<HttpRequest, HttpResponse> delegate) {
        return new HttpAuthServiceImpl(requireNonNull(delegate, "delegate"), authorizers);
    }

    /**
     * Returns a newly-created decorator that decorates a {@link Service} with a new {@link HttpAuthService}
     * based on the {@link Authorizer}s added to this builder.
     */
    public Function<Service<HttpRequest, HttpResponse>, HttpAuthService> newDecorator() {
        return HttpAuthService.newDecorator(authorizers);
    }
}
