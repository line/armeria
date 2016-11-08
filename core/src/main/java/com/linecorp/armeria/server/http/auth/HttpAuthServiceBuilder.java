/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.http.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.server.Service;

/**
 * Builds a new {@link HttpAuthService}.
 */
public final class HttpAuthServiceBuilder {

    private final List<Predicate<? super HttpHeaders>> predicates = new ArrayList<>();

    /**
     * Adds an authorization predicate.
     */
    public HttpAuthServiceBuilder add(Predicate<? super HttpHeaders> predicate) {
        predicates.add(predicate);
        return this;
    }

    /**
     * Adds multiple authorization predicates.
     */
    public HttpAuthServiceBuilder add(Iterable<? extends Predicate<? super HttpHeaders>> predicates) {
        this.predicates.addAll(Lists.newArrayList(predicates));
        return this;
    }

    /**
     * Adds an HTTP basic authorization predicate.
     */
    public HttpAuthServiceBuilder addBasicAuth(Predicate<? super BasicToken> predicate) {
        this.predicates.add(new Predicate<HttpHeaders>() {
            private final Function<HttpHeaders, BasicToken> extractor = AuthTokenExtractors.BASIC;

            @Override
            public boolean test(HttpHeaders headers) {
                BasicToken token = extractor.apply(headers);
                return token != null ? predicate.test(token) : false;
            }
        });
        return this;
    }

    /**
     * Adds an OAuth1a authorization predicate.
     */
    public HttpAuthServiceBuilder addOAuth1a(Predicate<? super OAuth1aToken> predicate) {
        this.predicates.add(new Predicate<HttpHeaders>() {
            private final Function<HttpHeaders, OAuth1aToken> extractor = AuthTokenExtractors.OAUTH1A;

            @Override
            public boolean test(HttpHeaders headers) {
                OAuth1aToken token = extractor.apply(headers);
                return token != null ? predicate.test(token) : false;
            }
        });
        return this;
    }

    /**
     * Adds an OAuth2 authorization predicate.
     */
    public HttpAuthServiceBuilder addOAuth2(Predicate<? super OAuth2Token> predicate) {
        this.predicates.add(new Predicate<HttpHeaders>() {
            private final Function<HttpHeaders, OAuth2Token> extractor = AuthTokenExtractors.OAUTH2;

            @Override
            public boolean test(HttpHeaders headers) {
                OAuth2Token token = extractor.apply(headers);
                return token != null ? predicate.test(token) : false;
            }
        });
        return this;
    }

    /**
     * Creates a new {@link HttpAuthService} instance with the given {@code delegate} and all of the
     * authorization {@link Predicate}s.
     */
    public HttpAuthService build(Service<? super HttpRequest, ? extends HttpResponse> delegate) {
        return new HttpAuthServiceImpl(delegate, Iterables.toArray(predicates, Predicate.class));
    }

    /**
     * Creates a new {@link HttpAuthService} {@link Service} decorator that supports all of the given
     * authorization {@link Predicate}s.
     */
    public Function<Service<? super HttpRequest, ? extends HttpResponse>, HttpAuthService> newDecorator() {
        return HttpAuthService.newDecorator(predicates);
    }
}
