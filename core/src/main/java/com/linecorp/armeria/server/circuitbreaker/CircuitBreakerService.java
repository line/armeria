/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.server.circuitbreaker;

import java.util.function.BiFunction;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.circuitbreaker.CircuitBreakerCallback;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Decorates an {@link HttpService} to circuit break the incoming requests.
 */
public final class CircuitBreakerService extends AbstractCircuitBreakerService<HttpRequest, HttpResponse>
        implements HttpService {

    /**
     * Returns a new {@link CircuitBreakerServiceBuilder}.
     */
    public static CircuitBreakerServiceBuilder builder() {
        return new CircuitBreakerServiceBuilder();
    }

    /**
     * Creates a new instance that decorates the specified {@link HttpService}.
     */
    CircuitBreakerService(
            HttpService delegate, CircuitBreakerRule rule, CircuitBreakerServiceHandler handler,
            BiFunction<? super ServiceRequestContext, ? super HttpRequest, ? extends HttpResponse> fallback) {
        super(delegate, rule, handler, fallback);
    }

    @Override
    protected HttpResponse doServe(ServiceRequestContext ctx, HttpRequest req, CircuitBreakerCallback callback)
            throws Exception {

        final HttpResponse response;

        try {
            response = unwrap().serve(ctx, req);
        } catch (Throwable cause) {
            // reportSuccessOrFailure(callback, rule.shouldReportAsSuccess(ctx, cause), ctx, cause);
            throw cause;
        }

        return response;
    }
}
