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
package com.linecorp.armeria.server.throttling;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Service;

/**
 * Builds a new {@link ThrottlingService}.
 */
public final class ThrottlingServiceBuilder
        extends AbstractThrottlingServiceBuilder<HttpRequest, HttpResponse> {

    /**
     * Provides default throttling reject behaviour for {@link HttpRequest}.
     * Returns an {@link HttpResponse} with {@link HttpStatus#TOO_MANY_REQUESTS}.
     */
    private static final ThrottlingRejectHandler<HttpRequest, HttpResponse> DEFAULT_REJECT_HANDLER =
            (delegate, ctx, req, cause) -> HttpResponse.of(HttpStatus.TOO_MANY_REQUESTS);

    ThrottlingServiceBuilder(ThrottlingStrategy<HttpRequest> strategy) {
        super(strategy, DEFAULT_REJECT_HANDLER);
    }

    /**
     * Sets {@link ThrottlingAcceptHandler}.
     */
    public ThrottlingServiceBuilder onAcceptedRequest(
            ThrottlingAcceptHandler<HttpRequest, HttpResponse> acceptHandler) {
        setAcceptHandler(acceptHandler);
        return this;
    }

    /**
     * Sets {@link ThrottlingRejectHandler}.
     */
    public ThrottlingServiceBuilder onRejectedRequest(
            ThrottlingRejectHandler<HttpRequest, HttpResponse> rejectHandler) {
        setRejectHandler(rejectHandler);
        return this;
    }

    /**
     * Returns a newly-created {@link ThrottlingService} based on the {@link ThrottlingStrategy}s added to
     * this builder.
     */
    public ThrottlingService build(HttpService delegate) {
        return new ThrottlingService(requireNonNull(delegate, "delegate"), getStrategy(),
                                     getAcceptHandler(), getRejectHandler());
    }

    /**
     * Returns a newly-created decorator that decorates an {@link Service} with a new
     * {@link ThrottlingService} based on the {@link ThrottlingStrategy}s added to this builder.
     */
    public Function<? super HttpService, ThrottlingService> newDecorator() {
        final ThrottlingStrategy<HttpRequest> strategy = getStrategy();
        final ThrottlingAcceptHandler<HttpRequest, HttpResponse> acceptHandler = getAcceptHandler();
        final ThrottlingRejectHandler<HttpRequest, HttpResponse> rejectHandler = getRejectHandler();
        return service ->
                new ThrottlingService(service, strategy, acceptHandler, rejectHandler);
    }
}
