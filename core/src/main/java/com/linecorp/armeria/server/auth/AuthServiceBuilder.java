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

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.BasicToken;
import com.linecorp.armeria.common.auth.OAuth1aToken;
import com.linecorp.armeria.common.auth.OAuth2Token;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Service;

/**
 * Builds a new {@link AuthService}.
 */
public final class AuthServiceBuilder {

    @Nullable
    private Authorizer<HttpRequest> authorizer;
    private AuthSuccessHandler successHandler = Service::serve;
    private AuthFailureHandler failureHandler = (delegate, ctx, req, cause) -> {
        if (cause != null) {
            AuthService.logger.warn("Unexpected exception during authorization.", cause);
        }
        return HttpResponse.of(HttpStatus.UNAUTHORIZED);
    };
    private MeterIdPrefix meterIdPrefix = new MeterIdPrefix("armeria.server.auth");

    /**
     * Creates a new instance.
     */
    AuthServiceBuilder() {}

    /**
     * Adds an {@link Authorizer}.
     */
    public AuthServiceBuilder add(Authorizer<HttpRequest> authorizer) {
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
    public AuthServiceBuilder add(Iterable<? extends Authorizer<HttpRequest>> authorizers) {
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
    public AuthServiceBuilder addBasicAuth(Authorizer<? super BasicToken> authorizer) {
        return addTokenAuthorizer(AuthTokenExtractors.basic(),
                                  requireNonNull(authorizer, "authorizer"));
    }

    /**
     * Adds an HTTP basic {@link Authorizer} for the given {@code header}.
     */
    public AuthServiceBuilder addBasicAuth(Authorizer<? super BasicToken> authorizer, CharSequence header) {
        return addTokenAuthorizer(new BasicTokenExtractor(requireNonNull(header, "header")),
                                  requireNonNull(authorizer, "authorizer"));
    }

    /**
     * Adds an OAuth1a {@link Authorizer}.
     */
    public AuthServiceBuilder addOAuth1a(Authorizer<? super OAuth1aToken> authorizer) {
        return addTokenAuthorizer(AuthTokenExtractors.oAuth1a(),
                                  requireNonNull(authorizer, "authorizer"));
    }

    /**
     * Adds an OAuth1a {@link Authorizer} for the given {@code header}.
     */
    public AuthServiceBuilder addOAuth1a(Authorizer<? super OAuth1aToken> authorizer, CharSequence header) {
        return addTokenAuthorizer(new OAuth1aTokenExtractor(requireNonNull(header, "header")),
                                  requireNonNull(authorizer, "authorizer"));
    }

    /**
     * Adds an OAuth2 {@link Authorizer}.
     */
    public AuthServiceBuilder addOAuth2(Authorizer<? super OAuth2Token> authorizer) {
        return addTokenAuthorizer(AuthTokenExtractors.oAuth2(), requireNonNull(authorizer, "authorizer"));
    }

    /**
     * Adds an OAuth2 {@link Authorizer} for the given {@code header}.
     */
    public AuthServiceBuilder addOAuth2(Authorizer<? super OAuth2Token> authorizer, CharSequence header) {
        return addTokenAuthorizer(new OAuth2TokenExtractor(requireNonNull(header, "header")),
                                  requireNonNull(authorizer, "authorizer"));
    }

    /**
     * Adds a token-based {@link Authorizer}.
     */
    public <T> AuthServiceBuilder addTokenAuthorizer(
            Function<? super RequestHeaders, @Nullable T> tokenExtractor, Authorizer<? super T> authorizer) {
        final Authorizer<HttpRequest> requestAuthorizer =
                new DelegatingHttpRequestAuthorizer<>(tokenExtractor, authorizer);
        add(requestAuthorizer);
        return this;
    }

    /**
     * Sets the {@link AuthSuccessHandler} which handles successfully authorized requests.
     * By default, the request will be delegated to the next {@link HttpService}.
     */
    public AuthServiceBuilder onSuccess(AuthSuccessHandler successHandler) {
        this.successHandler = requireNonNull(successHandler, "successHandler");
        return this;
    }

    /**
     * Sets the {@link AuthFailureHandler} which handles the requests with failed authorization.
     * By default, an exception thrown during authorization is logged at WARN level (if any) and a
     * {@code 401 Unauthorized} response will be sent.
     */
    public AuthServiceBuilder onFailure(AuthFailureHandler failureHandler) {
        this.failureHandler = requireNonNull(failureHandler, "failureHandler");
        return this;
    }

    /**
     * Sets the {@link MeterIdPrefix} pattern to which metrics will be collected.
     * By default, {@code armeria.server.auth} will be used as the metric name.
     * <table>
     * <caption>Metrics that will be generated by this class</caption>
     * <tr>
     *   <th>metric name</th>
     *   <th>description</th>
     * </tr>
     * <tr>
     *   <td>{@code <name>#count{result="success"}}</td>
     *   <td>The number of successful authentication requests.</td>
     * </tr>
     * <tr>
     *   <td>{@code <name>#count{result="failure"}}</td>
     *   <td>The number of failed authentication requests.</td>
     * </tr>
     * </table>
     */
    public AuthServiceBuilder meterIdPrefix(MeterIdPrefix meterIdPrefix) {
        this.meterIdPrefix = requireNonNull(meterIdPrefix, "meterIdPrefix");
        return this;
    }

    /**
     * Returns a newly-created {@link AuthService} based on the {@link Authorizer}s added to this builder.
     */
    public AuthService build(HttpService delegate) {
        return new AuthService(requireNonNull(delegate, "delegate"), authorizer(),
                               successHandler, failureHandler, meterIdPrefix);
    }

    private AuthService build(HttpService delegate, Authorizer<HttpRequest> authorizer) {
        return new AuthService(requireNonNull(delegate, "delegate"), authorizer,
                               successHandler, failureHandler, meterIdPrefix);
    }

    /**
     * Returns a newly-created decorator that decorates an {@link HttpService} with a new
     * {@link AuthService} based on the {@link Authorizer}s added to this builder.
     */
    public Function<? super HttpService, AuthService> newDecorator() {
        final Authorizer<HttpRequest> authorizer = authorizer();
        return delegate -> build(delegate, authorizer);
    }

    private Authorizer<HttpRequest> authorizer() {
        if (authorizer == null) {
            throw new IllegalStateException("no " + Authorizer.class.getSimpleName() + " was added.");
        }
        return authorizer;
    }
}
