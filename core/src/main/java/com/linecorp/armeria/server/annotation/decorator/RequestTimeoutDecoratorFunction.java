/*
 * Copyright 2022 LINE Corporation
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
package com.linecorp.armeria.server.annotation.decorator;

import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;

/**
 * A factory which creates a decorator that sets request timeout to current {@link ServiceRequestContext}.
 */
public final class RequestTimeoutDecoratorFunction implements DecoratorFactoryFunction<RequestTimeout> {

    /**
     * Creates a new decorator with the specified {@link RequestTimeout}.
     */
    @Override
    public Function<? super HttpService, ? extends HttpService> newDecorator(RequestTimeout parameter) {
        final long timeoutMillis = parameter.unit().toMillis(parameter.value());
        return delegate -> new SimpleDecoratingHttpService(delegate) {
            @Override
            public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                if (timeoutMillis <= 0) {
                    ctx.clearRequestTimeout();
                } else {
                    ctx.setRequestTimeoutMillis(TimeoutMode.SET_FROM_START, timeoutMillis);
                }
                return delegate.serve(ctx, req);
            }
        };
    }
}
