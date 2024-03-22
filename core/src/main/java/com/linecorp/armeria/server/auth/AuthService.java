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

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

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
    @Nullable
    private Timer successTimer;
    @Nullable
    private Timer failureTimer;
    private final MeterIdPrefix meterIdPrefix;

    AuthService(HttpService delegate, Authorizer<HttpRequest> authorizer,
                AuthSuccessHandler defaultSuccessHandler, AuthFailureHandler defaultFailureHandler,
                MeterIdPrefix meterIdPrefix) {
        super(delegate);
        this.authorizer = authorizer;
        this.defaultSuccessHandler = defaultSuccessHandler;
        this.defaultFailureHandler = defaultFailureHandler;
        this.meterIdPrefix = meterIdPrefix;
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        super.serviceAdded(cfg);
        final MeterRegistry meterRegistry = cfg.server().meterRegistry();
        successTimer = MoreMeters.newTimer(meterRegistry, meterIdPrefix.name(),
                                           meterIdPrefix.tags("result", "success"));
        failureTimer = MoreMeters.newTimer(meterRegistry, meterIdPrefix.name(),
                                           meterIdPrefix.tags("result", "failure"));
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final long startNanos = System.nanoTime();

        return HttpResponse.of(AuthorizerUtil.authorizeAndSupplyHandlers(authorizer, ctx, req)
                                             .handleAsync((result, cause) -> {
            try {
                final HttpService delegate = (HttpService) unwrap();
                if (cause == null) {
                    if (result != null) {
                        if (!result.isAuthorized()) {
                            return handleFailure(delegate, result.failureHandler(), ctx, req, null, startNanos);
                        }
                        return handleSuccess(delegate, result.successHandler(), ctx, req, startNanos);
                    }
                    cause = AuthorizerUtil.newNullResultException(authorizer);
                }

                return handleFailure(delegate, result != null ? result.failureHandler() : null,
                                     ctx, req, cause, startNanos);
            } catch (Exception e) {
                return Exceptions.throwUnsafely(e);
            }
        }, ctx.eventLoop()));
    }

    private HttpResponse handleSuccess(HttpService delegate,
                                       @Nullable AuthSuccessHandler authorizerSuccessHandler,
                                       ServiceRequestContext ctx, HttpRequest req,
                                       long startNanos) throws Exception {
        assert successTimer != null;
        successTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        final AuthSuccessHandler handler = authorizerSuccessHandler == null ? defaultSuccessHandler
                                                                            : authorizerSuccessHandler;
        return handler.authSucceeded(delegate, ctx, req);
    }

    private HttpResponse handleFailure(HttpService delegate,
                                       @Nullable AuthFailureHandler authorizerFailureHandler,
                                       ServiceRequestContext ctx, HttpRequest req,
                                       @Nullable Throwable cause, long startNanos) throws Exception {
        assert failureTimer != null;
        failureTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        final AuthFailureHandler handler = authorizerFailureHandler == null ? defaultFailureHandler
                                                                            : authorizerFailureHandler;
        if (cause != null) {
            cause = Exceptions.peel(cause);
        }
        return handler.authFailed(delegate, ctx, req, cause);
    }
}
