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

import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;

/**
 * Builds a new {@link AuthService}.
 *
 * @deprecated Use {@link AuthService#builder()}.
 */
@Deprecated
public final class HttpAuthServiceBuilder extends AuthServiceBuilder {

    @Override
    public HttpAuthServiceBuilder add(Authorizer<HttpRequest> authorizer) {
        return (HttpAuthServiceBuilder) super.add(authorizer);
    }

    @Override
    public HttpAuthServiceBuilder add(Iterable<? extends Authorizer<HttpRequest>> authorizers) {
        return (HttpAuthServiceBuilder) super.add(authorizers);
    }

    @Override
    public HttpAuthServiceBuilder addBasicAuth(Authorizer<? super BasicToken> authorizer) {
        return (HttpAuthServiceBuilder) super.addBasicAuth(authorizer);
    }

    @Override
    public HttpAuthServiceBuilder addBasicAuth(Authorizer<? super BasicToken> authorizer, CharSequence header) {
        return (HttpAuthServiceBuilder) super.addBasicAuth(authorizer, header);
    }

    @Override
    public HttpAuthServiceBuilder addOAuth1a(Authorizer<? super OAuth1aToken> authorizer) {
        return (HttpAuthServiceBuilder) super.addOAuth1a(authorizer);
    }

    @Override
    public HttpAuthServiceBuilder addOAuth1a(Authorizer<? super OAuth1aToken> authorizer, CharSequence header) {
        return (HttpAuthServiceBuilder) super.addOAuth1a(authorizer, header);
    }

    @Override
    public HttpAuthServiceBuilder addOAuth2(Authorizer<? super OAuth2Token> authorizer) {
        return (HttpAuthServiceBuilder) super.addOAuth2(authorizer);
    }

    @Override
    public HttpAuthServiceBuilder addOAuth2(Authorizer<? super OAuth2Token> authorizer, CharSequence header) {
        return (HttpAuthServiceBuilder) super.addOAuth2(authorizer, header);
    }

    @Override
    public <T> HttpAuthServiceBuilder addTokenAuthorizer(Function<? super RequestHeaders, T> tokenExtractor,
                                                         Authorizer<? super T> authorizer) {
        return (HttpAuthServiceBuilder) super.addTokenAuthorizer(tokenExtractor, authorizer);
    }

    @Override
    public HttpAuthServiceBuilder onSuccess(AuthSuccessHandler<HttpRequest, HttpResponse> successHandler) {
        return (HttpAuthServiceBuilder) super.onSuccess(successHandler);
    }

    @Override
    public HttpAuthServiceBuilder onFailure(AuthFailureHandler<HttpRequest, HttpResponse> failureHandler) {
        return (HttpAuthServiceBuilder) super.onFailure(failureHandler);
    }
}
