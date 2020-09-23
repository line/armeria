/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.util.Unwrappable;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.metric.MetricCollectingService;

/**
 * Handles a {@link Request} received by a {@link Server}.
 *
 * @param <I> the type of incoming {@link Request}. Must be {@link HttpRequest} or {@link RpcRequest}.
 * @param <O> the type of outgoing {@link Response}. Must be {@link HttpResponse} or {@link RpcResponse}.
 */
@FunctionalInterface
public interface Service<I extends Request, O extends Response> extends Unwrappable {

    /**
     * Invoked when this service has been added to a {@link Server} with the specified
     * configuration. Please note that this method can be invoked more than once if this service
     * has been added more than once.
     */
    default void serviceAdded(ServiceConfig cfg) throws Exception {}

    /**
     * Serves an incoming {@link Request}.
     *
     * @param ctx the context of the received {@link Request}
     * @param req the received {@link Request}
     *
     * @return the {@link Response}
     */
    O serve(ServiceRequestContext ctx, I req) throws Exception;

    /**
     * Unwraps this {@link Service} into the object of the specified {@code type}.
     * Use this method instead of an explicit downcast. For example:
     * <pre>{@code
     * HttpService s = new MyService().decorate(LoggingService.newDecorator())
     *                                .decorate(AuthService.newDecorator());
     * MyService s1 = s.as(MyService.class);
     * LoggingService s2 = s.as(LoggingService.class);
     * AuthService s3 = s.as(AuthService.class);
     * }</pre>
     *
     * @param type the type of the object to return
     * @return the object of the specified {@code type} if found, or {@code null} if not found.
     *
     * @see Unwrappable
     */
    @Override
    default <T> T as(Class<T> type) {
        requireNonNull(type, "type");
        return Unwrappable.super.as(type);
    }

    /**
     * Unwraps this {@link Service} and returns the object being decorated.
     * If this {@link Service} is the innermost object, this method returns itself.
     * For example:
     * <pre>{@code
     * HttpService service1 = new MyService();
     * assert service1.unwrap() == service1;
     *
     * HttpService service2 = service1.decorate(LoggingService.newDecorator());
     * HttpService service3 = service2.decorate(AuthService.newDecorator());
     * assert service2.unwrap() == service1;
     * assert service3.unwrap() == service2;
     * assert service3.unwrap().unwrap() == service1;
     * }</pre>
     */
    @Override
    default Service<? extends Request, ? extends Response> unwrap() {
        return (Service<? extends Request, ? extends Response>) Unwrappable.super.unwrap();
    }

    /**
     * Returns whether the given {@code path} and {@code query} should be cached if the service's result is
     * successful. By default, exact path mappings with no input query are cached.
     */
    default boolean shouldCachePath(String path, @Nullable String query, Route route) {
        return route.pathType() == RoutePathType.EXACT && query == null;
    }

    /**
     * Returns whether the request and response are logged or recorded by
     * {@link LoggingService}, {@link MetricCollectingService} and {@link AccessLogWriter}.
     */
    default boolean shouldLogRequest() {
        return true;
    }
}
