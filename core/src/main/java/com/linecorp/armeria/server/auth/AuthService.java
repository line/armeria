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

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

/**
 * Decorates an {@link HttpService} to provide HTTP authorization functionality.
 *
 * @see AuthServiceBuilder
 */
public final class AuthService extends SimpleDecoratingHttpService {

    static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    /**
     * Creates a new HTTP authorization {@link HttpService} decorator using the specified
     * {@link Authorizer}s.
     *
     * @param authorizers a list of {@link Authorizer}s.
     */
    public static Function<? super HttpService, AuthService> newDecorator(
            Iterable<? extends Authorizer<HttpRequest>> authorizers) {
        return builder().add(authorizers).newDecorator();
    }

    /**
     * Creates a new HTTP authorization {@link HttpService} decorator using the specified
     * {@link Authorizer}s.
     *
     * @param authorizers the array of {@link Authorizer}s.
     */
    @SafeVarargs
    public static Function<? super HttpService, AuthService>
    newDecorator(Authorizer<HttpRequest>... authorizers) {
        return newDecorator(ImmutableList.copyOf(requireNonNull(authorizers, "authorizers")));
    }

    /**
     * Returns a new {@link AuthServiceBuilder}.
     */
    public static AuthServiceBuilder builder() {
        return new AuthServiceBuilder();
    }

    private final Authorizer<HttpRequest> authorizer;
    private final AuthSuccessHandler defaultSuccessHandler;
    private final AuthFailureHandler defaultFailureHandler;

    AuthService(HttpService delegate, Authorizer<HttpRequest> authorizer,
                AuthSuccessHandler defaultSuccessHandler, AuthFailureHandler defaultFailureHandler) {
        super(delegate);
        this.authorizer = authorizer;
        this.defaultSuccessHandler = defaultSuccessHandler;
        this.defaultFailureHandler = defaultFailureHandler;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return HttpResponse.from(AuthorizerUtil.authorize(authorizer, ctx, req).handleAsync((result, cause) -> {
            try {
                final HttpService delegate = (HttpService) unwrap();
                if (cause == null) {
                    if (result != null) {
                        return result ? handleSuccess(delegate, ctx, req)
                                      : handleFailure(delegate, ctx, req, null);
                    }
                    cause = AuthorizerUtil.newNullResultException(authorizer);
                }

                return handleFailure(delegate, ctx, req, cause);
            } catch (Exception e) {
                return Exceptions.throwUnsafely(e);
            }
        }, ctx.eventLoop()));
    }

    private HttpResponse handleSuccess(HttpService delegate, ServiceRequestContext ctx, HttpRequest req)
            throws Exception {
        final AuthSuccessHandler authorizerSuccessHandler = authorizer.successHandler();
        final AuthSuccessHandler handler = authorizerSuccessHandler == null ? defaultSuccessHandler
                                                                            : authorizerSuccessHandler;
        return handler.authSucceeded(delegate, ctx, req);
    }

    private HttpResponse handleFailure(HttpService delegate, ServiceRequestContext ctx, HttpRequest req,
                                       @Nullable Throwable cause) throws Exception {
        final AuthFailureHandler authorizerFailureHandler = authorizer.failureHandler();
        final AuthFailureHandler handler = authorizerFailureHandler == null ? defaultFailureHandler
                                                                            : authorizerFailureHandler;
        return handler.authFailed(delegate, ctx, req, cause);
    }
}
