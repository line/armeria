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

import java.util.function.Function;
import java.util.function.Predicate;

import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.http.DefaultHttpResponse;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.server.DecoratingService;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A {@link DecoratingService} that provides HTTP authorization functionality.
 */
public abstract class HttpAuthService
        extends DecoratingService<HttpRequest, HttpResponse, HttpRequest, HttpResponse> {

    /**
     * Creates a new HTTP authorization {@link Service} decorator using the specified
     * {@code predicates}.
     *
     * @param predicates {@link Iterable} authorization predicates
     */
    public static Function<Service<? super HttpRequest, ? extends HttpResponse>,
            HttpAuthService> newDecorator(Iterable<? extends Predicate<? super HttpHeaders>> predicates) {
        Predicate<? super HttpHeaders>[] array = Iterables.toArray(predicates, Predicate.class);
        return newDecorator(array);
    }

    /**
     * Creates a new HTTP authorization {@link Service} decorator using the specified
     * {@code predicates}.
     *
     * @param predicates the array of authorization predicates
     */
    public static Function<Service<? super HttpRequest, ? extends HttpResponse>,
            HttpAuthService> newDecorator(Predicate<? super HttpHeaders>... predicates) {
        return service -> new HttpAuthServiceImpl(service, predicates);
    }

    /**
     * Creates a new instance that provides HTTP authorization functionality to {@code delegate}.
     */
    protected HttpAuthService(Service<? super HttpRequest, ? extends HttpResponse> delegate) {
        super(delegate);
    }

    /**
     * Authorize {@code headers}. In other words, determine if it is successful or not.
     */
    protected abstract boolean authorize(HttpHeaders headers);

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
    protected HttpResponse onFailure(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final DefaultHttpResponse res = new DefaultHttpResponse();
        res.respond(HttpStatus.UNAUTHORIZED);
        return res;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        if (authorize(req.headers())) {
            return onSuccess(ctx, req);
        } else {
            return onFailure(ctx, req);
        }
    }
}
