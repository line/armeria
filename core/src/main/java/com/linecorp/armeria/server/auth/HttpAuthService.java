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

import java.util.function.Function;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;

/**
 * Decorates an {@link HttpService} to provide HTTP authorization functionality.
 *
 * @deprecated Use {@link AuthService}.
 */
@Deprecated
public final class HttpAuthService extends AuthService {

    /**
     * Creates a new HTTP authorization {@link HttpService} decorator using the specified
     * {@link Authorizer}s.
     *
     * @param authorizers a list of {@link Authorizer}s.
     * @deprecated Use {@link AuthService#newDecorator(Iterable)}.
     */
    @Deprecated
    public static Function<? super HttpService, AuthService> newDecorator(
            Iterable<? extends Authorizer<HttpRequest>> authorizers) {
        return new AuthServiceBuilder().add(authorizers).newDecorator();
    }

    /**
     * Creates a new HTTP authorization {@link HttpService} decorator using the specified
     * {@link Authorizer}s.
     *
     * @param authorizers the array of {@link Authorizer}s.
     * @deprecated Use {@link AuthService#newDecorator(Authorizer[])}.
     */
    @SafeVarargs
    @Deprecated
    public static Function<? super HttpService, AuthService>
    newDecorator(Authorizer<HttpRequest>... authorizers) {
        return newDecorator(ImmutableList.copyOf(requireNonNull(authorizers, "authorizers")));
    }

    HttpAuthService(HttpService delegate,
                    Authorizer<HttpRequest> authorizer,
                    AuthSuccessHandler<HttpRequest, HttpResponse> successHandler,
                    AuthFailureHandler<HttpRequest, HttpResponse> failureHandler) {
        super(delegate, authorizer, successHandler, failureHandler);
    }
}
