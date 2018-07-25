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

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Service;

import io.netty.util.AsciiString;

/**
 * Builds a new {@link HttpAuthService}.
 */
public final class HttpAuthServiceBuilder {

    @Nullable
    private Authorizer<HttpRequest> authorizer;
    private AuthSuccessHandler<HttpRequest, HttpResponse> successHandler = Service::serve;
    private AuthFailureHandler<HttpRequest, HttpResponse> failureHandler = (delegate, ctx, req, cause) -> {
        if (cause != null) {
            HttpAuthService.logger.warn("Unexpected exception during authorization.", cause);
        }
        return HttpResponse.of(HttpStatus.UNAUTHORIZED);
    };

    /**
     * Adds an {@link Authorizer}.
     */
    public HttpAuthServiceBuilder add(Authorizer<HttpRequest> authorizer) {
        requireNonNull(authorizer, "authorizer");
        if (this.authorizer == null) {
            this.authorizer = authorizer;
        } else {
            this.authorizer = this.authorizer.orElse(authorizer);
        }
        return this;
    }

    /**
     * Adds multiple {@link Authorizer}s.
     */
    public HttpAuthServiceBuilder add(Iterable<? extends Authorizer<HttpRequest>> authorizers) {
        requireNonNull(authorizers, "authorizers");
        authorizers.forEach(a -> {
            requireNonNull(a, "authorizers contains null.");
            add(a);
        });
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
            final T token = tokenExtractor.apply(req.headers());
            if (token == null) {
                return CompletableFuture.completedFuture(false);
            }
            return authorizer.authorize(ctx, token);
        };
        add(requestAuthorizer);
        return this;
    }

    /**
     * Sets the {@link AuthSuccessHandler} which handles successfully authorized requests.
     * By default, the request will be delegated to the next {@link Service}.
     */
    public HttpAuthServiceBuilder onSuccess(AuthSuccessHandler<HttpRequest, HttpResponse> successHandler) {
        this.successHandler = requireNonNull(successHandler, "successHandler");
        return this;
    }

    /**
     * Sets the {@link AuthFailureHandler} which handles the requests with failed authorization.
     * By default, an exception thrown during authorization is logged at WARN level (if any) and a
     * {@code 401 Unauthorized} response will be sent.
     */
    public HttpAuthServiceBuilder onFailure(AuthFailureHandler<HttpRequest, HttpResponse> failureHandler) {
        this.failureHandler = requireNonNull(failureHandler, "failureHandler");
        return this;
    }

    /**
     * Returns a newly-created {@link HttpAuthService} based on the {@link Authorizer}s added to this builder.
     */
    public HttpAuthService build(Service<HttpRequest, HttpResponse> delegate) {
        return new HttpAuthService(requireNonNull(delegate, "delegate"), authorizer(),
                                   successHandler, failureHandler);
    }

    /**
     * Returns a newly-created decorator that decorates a {@link Service} with a new {@link HttpAuthService}
     * based on the {@link Authorizer}s added to this builder.
     */
    public Function<Service<HttpRequest, HttpResponse>, HttpAuthService> newDecorator() {
        final Authorizer<HttpRequest> authorizer = authorizer();
        final AuthSuccessHandler<HttpRequest, HttpResponse> successHandler = this.successHandler;
        final AuthFailureHandler<HttpRequest, HttpResponse> failureHandler = this.failureHandler;
        return service -> new HttpAuthService(service, authorizer, successHandler, failureHandler);
    }

    private Authorizer<HttpRequest> authorizer() {
        if (authorizer == null) {
            throw new IllegalStateException("no " + Authorizer.class.getSimpleName() + " was added.");
        }
        return authorizer;
    }
}
