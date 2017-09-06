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

import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.DefaultHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.DecoratingService;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;

/**
 * A {@link DecoratingService} that provides HTTP authorization functionality.
 */
public abstract class HttpAuthService extends SimpleDecoratingService<HttpRequest, HttpResponse> {

    private static final Logger logger = LoggerFactory.getLogger(HttpAuthService.class);

    /**
     * Creates a new HTTP authorization {@link Service} decorator using the specified
     * {@link Authorizer}s.
     *
     * @param authorizers a list of {@link Authorizer}s.
     */
    public static Function<Service<HttpRequest, HttpResponse>, HttpAuthService> newDecorator(
            Iterable<? extends Authorizer<HttpRequest>> authorizers) {
        return service -> new HttpAuthServiceImpl(service, authorizers);
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

    /**
     * Creates a new instance that provides HTTP authorization functionality to {@code delegate}.
     */
    protected HttpAuthService(Service<HttpRequest, HttpResponse> delegate) {
        super(delegate);
    }

    /**
     * Determine if {@code request} is authorized for this service. If the result resolves to
     * {@code true}, the request is authorized, or {@code false} otherwise. If the future
     * resolves exceptionally, the request will not be authorized.
     */
    protected abstract CompletionStage<Boolean> authorize(HttpRequest request, ServiceRequestContext ctx);

    /**
     * Invoked when {@code req} is successful. By default, this method delegates the specified {@code req} to
     * the {@link #delegate()} of this service.
     */
    protected HttpResponse onSuccess(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return delegate().serve(ctx, req);
    }

    /**
     * Invoked when {@code req} is failed. By default, this method responds with the
     * {@link HttpStatus#UNAUTHORIZED} status.
     */
    protected HttpResponse onFailure(ServiceRequestContext ctx, HttpRequest req, @Nullable Throwable cause)
            throws Exception {
        if (cause != null) {
            logger.warn("Unexpected exception during authorization.", cause);
        }
        final DefaultHttpResponse res = new DefaultHttpResponse();
        res.respond(HttpStatus.UNAUTHORIZED);
        return res;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return HttpResponse.from(authorize(req, ctx).handleAsync((result, t) -> {
            try {
                if (t != null || !result) {
                    return onFailure(ctx, req, t);
                } else {
                    return onSuccess(ctx, req);
                }
            } catch (Exception e) {
                return Exceptions.throwUnsafely(e);
            }
        }, ctx.contextAwareEventLoop()));
    }
}
