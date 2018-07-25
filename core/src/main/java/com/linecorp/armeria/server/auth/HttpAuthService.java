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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;

/**
 * Decorates a {@link Service} to provide HTTP authorization functionality.
 *
 * @see HttpAuthServiceBuilder
 */
public final class HttpAuthService extends SimpleDecoratingService<HttpRequest, HttpResponse> {

    static final Logger logger = LoggerFactory.getLogger(HttpAuthService.class);

    /**
     * Creates a new HTTP authorization {@link Service} decorator using the specified
     * {@link Authorizer}s.
     *
     * @param authorizers a list of {@link Authorizer}s.
     */
    public static Function<Service<HttpRequest, HttpResponse>, HttpAuthService> newDecorator(
            Iterable<? extends Authorizer<HttpRequest>> authorizers) {
        return new HttpAuthServiceBuilder().add(authorizers).newDecorator();
    }

    /**
     * Creates a new HTTP authorization {@link Service} decorator using the specified
     * {@link Authorizer}s.
     *
     * @param authorizers the array of {@link Authorizer}s.
     */
    @SafeVarargs
    public static Function<Service<HttpRequest, HttpResponse>, HttpAuthService>
    newDecorator(Authorizer<HttpRequest>... authorizers) {
        return newDecorator(ImmutableList.copyOf(requireNonNull(authorizers, "authorizers")));
    }

    private final Authorizer<HttpRequest> authorizer;
    private final AuthSuccessHandler<HttpRequest, HttpResponse> successHandler;
    private final AuthFailureHandler<HttpRequest, HttpResponse> failureHandler;

    HttpAuthService(Service<HttpRequest, HttpResponse> delegate, Authorizer<HttpRequest> authorizer,
                    AuthSuccessHandler<HttpRequest, HttpResponse> successHandler,
                    AuthFailureHandler<HttpRequest, HttpResponse> failureHandler) {
        super(delegate);
        this.authorizer = authorizer;
        this.successHandler = successHandler;
        this.failureHandler = failureHandler;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return HttpResponse.from(AuthorizerUtil.authorize(authorizer, ctx, req).handleAsync((result, cause) -> {
            try {
                if (cause == null) {
                    if (result != null) {
                        return result ? successHandler.authSucceeded(delegate(), ctx, req)
                                      : failureHandler.authFailed(delegate(), ctx, req, null);
                    }
                    cause = AuthorizerUtil.newNullResultException(authorizer);
                }

                return failureHandler.authFailed(delegate(), ctx, req, cause);
            } catch (Exception e) {
                return Exceptions.throwUnsafely(e);
            }
        }, ctx.contextAwareEventLoop()));
    }
}
