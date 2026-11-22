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

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
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

    private static final String BASIC_SCHEME = "Basic";
    private static final String OAUTH1A_SCHEME = "OAuth";
    private static final String OAUTH2_SCHEME = "Bearer";

    @Nullable
    private Authorizer<HttpRequest> authorizer;
    private AuthSuccessHandler successHandler = Service::serve;
    @Nullable
    private AuthFailureHandler failureHandler;
    @Nullable
    private String realm;
    // The schemes of the authorizers which extract a token from the standard 'Authorization' header.
    private final Set<String> schemes = new LinkedHashSet<>();
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
        addTokenAuthorizer(AuthTokenExtractors.basic(), requireNonNull(authorizer, "authorizer"));
        schemes.add(BASIC_SCHEME);
        return this;
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
        addTokenAuthorizer(AuthTokenExtractors.oAuth1a(), requireNonNull(authorizer, "authorizer"));
        schemes.add(OAUTH1A_SCHEME);
        return this;
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
        addTokenAuthorizer(AuthTokenExtractors.oAuth2(), requireNonNull(authorizer, "authorizer"));
        schemes.add(OAUTH2_SCHEME);
        return this;
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
     * {@code 401 Unauthorized} response will be sent. If a {@linkplain #realm(String) realm} is specified,
     * the response also contains {@code WWW-Authenticate} headers. A handler specified by this method
     * always takes precedence over the default one.
     */
    public AuthServiceBuilder onFailure(AuthFailureHandler failureHandler) {
        this.failureHandler = requireNonNull(failureHandler, "failureHandler");
        return this;
    }

    /**
     * Sets the realm which is used to challenge unauthorized requests. If specified, the default
     * {@link AuthFailureHandler} sends a {@code 401 Unauthorized} response with a {@code WWW-Authenticate}
     * header per authentication scheme of the authorizers added by {@link #addBasicAuth(Authorizer)},
     * {@link #addOAuth1a(Authorizer)} and {@link #addOAuth2(Authorizer)}, e.g.
     * <pre>{@code
     * AuthService.builder()
     *            .addBasicAuth(basicAuthorizer)
     *            .addOAuth2(oAuth2Authorizer)
     *            .realm("My Service")
     *            .newDecorator();
     *
     * // The following headers will be returned for unauthorized requests:
     * // 401 Unauthorized
     * // www-authenticate: Basic realm="My Service", charset="UTF-8"
     * // www-authenticate: Bearer realm="My Service"
     * }</pre>
     * Note that browsers may display a login dialog when they receive a {@code Basic} challenge, which is why
     * no challenge is sent unless a realm is specified. The authorizers added with a custom header, such as
     * {@link #addBasicAuth(Authorizer, CharSequence)}, are not challenged. This setting has no effect
     * if a handler is specified via {@link #onFailure(AuthFailureHandler)} or if an {@link Authorizer}
     * returns an {@link AuthorizationStatus} which has its own {@link AuthFailureHandler}.
     */
    @UnstableApi
    public AuthServiceBuilder realm(String realm) {
        this.realm = requireNonNull(realm, "realm");
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
                               successHandler, failureHandler(), meterIdPrefix);
    }

    private AuthService build(HttpService delegate, Authorizer<HttpRequest> authorizer,
                              AuthFailureHandler failureHandler) {
        return new AuthService(requireNonNull(delegate, "delegate"), authorizer,
                               successHandler, failureHandler, meterIdPrefix);
    }

    /**
     * Returns a newly-created decorator that decorates an {@link HttpService} with a new
     * {@link AuthService} based on the {@link Authorizer}s added to this builder.
     */
    public Function<? super HttpService, AuthService> newDecorator() {
        final Authorizer<HttpRequest> authorizer = authorizer();
        final AuthFailureHandler failureHandler = failureHandler();
        return delegate -> build(delegate, authorizer, failureHandler);
    }

    private AuthFailureHandler failureHandler() {
        final AuthFailureHandler failureHandler = this.failureHandler;
        if (failureHandler != null) {
            return failureHandler;
        }
        final ResponseHeaders headers = unauthorizedHeaders(realm, ImmutableSet.copyOf(schemes));
        return (delegate, ctx, req, cause) -> {
            if (cause != null) {
                AuthService.logger.warn("Unexpected exception during authorization.", cause);
            }
            return HttpResponse.of(headers);
        };
    }

    private static ResponseHeaders unauthorizedHeaders(@Nullable String realm, Set<String> schemes) {
        if (realm == null || schemes.isEmpty()) {
            return ResponseHeaders.of(HttpStatus.UNAUTHORIZED);
        }
        final ResponseHeadersBuilder builder = ResponseHeaders.builder(HttpStatus.UNAUTHORIZED);
        final String quotedRealm = '"' + realm.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        for (String scheme : schemes) {
            final String challenge = scheme + " realm=" + quotedRealm;
            builder.add(HttpHeaderNames.WWW_AUTHENTICATE,
                        BASIC_SCHEME.equals(scheme) ? challenge + ", charset=\"UTF-8\"" : challenge);
        }
        return builder.build();
    }

    private Authorizer<HttpRequest> authorizer() {
        if (authorizer == null) {
            throw new IllegalStateException("no " + Authorizer.class.getSimpleName() + " was added.");
        }
        return authorizer;
    }
}
