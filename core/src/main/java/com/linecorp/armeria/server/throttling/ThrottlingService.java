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

import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;

/**
 * Decorates an {@link HttpService} to throttle incoming requests.
 */
public final class ThrottlingService extends AbstractThrottlingService<HttpRequest, HttpResponse>
        implements HttpService {
    /**
     * Creates a new decorator using the specified {@link ThrottlingStrategy} and
     * {@link ThrottlingRejectHandler}.
     *
     * @param strategy The {@link ThrottlingStrategy} instance to define throttling strategy
     * @param rejectHandler The {@link ThrottlingRejectHandler} instance to define request rejection behaviour
     */
    public static Function<? super HttpService, ThrottlingService>
    newDecorator(ThrottlingStrategy<HttpRequest> strategy,
                 ThrottlingRejectHandler<HttpRequest, HttpResponse> rejectHandler) {
        return builder(strategy).onRejectedRequest(rejectHandler).newDecorator();
    }

    /**
     * Creates a new decorator using the specified {@link ThrottlingStrategy}.
     *
     * @param strategy The {@link ThrottlingStrategy} instance to define throttling strategy
     */
    public static Function<? super HttpService, ThrottlingService>
    newDecorator(ThrottlingStrategy<HttpRequest> strategy) {
        return builder(strategy).newDecorator();
    }

    /**
     * Returns a new {@link ThrottlingServiceBuilder}.
     */
    public static ThrottlingServiceBuilder builder(ThrottlingStrategy<HttpRequest> strategy) {
        return new ThrottlingServiceBuilder(strategy);
    }

    /**
     * Creates a new instance that decorates the specified {@link HttpService}.
     */
    ThrottlingService(HttpService delegate, ThrottlingStrategy<HttpRequest> strategy,
                      ThrottlingAcceptHandler<HttpRequest, HttpResponse> acceptHandler,
                      ThrottlingRejectHandler<HttpRequest, HttpResponse> rejectHandler) {
        super(delegate, strategy, HttpResponse::of, acceptHandler, rejectHandler);
    }
}
