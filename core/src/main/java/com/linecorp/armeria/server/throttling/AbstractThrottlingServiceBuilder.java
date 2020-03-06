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

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.server.Service;

/**
 * Builds a new {@link AbstractThrottlingService}.
 * @param <I> type of the request
 * @param <O> type of the response
 */
abstract class AbstractThrottlingServiceBuilder<I extends Request, O extends Response> {

    private final ThrottlingStrategy<I> strategy;
    private ThrottlingAcceptHandler<I, O> acceptHandler;
    private ThrottlingRejectHandler<I, O> rejectHandler;

    AbstractThrottlingServiceBuilder(ThrottlingStrategy<I> strategy,
                                     ThrottlingRejectHandler<I, O> defaultRejectHandler) {
        this.strategy = requireNonNull(strategy, "strategy");
        acceptHandler = Service::serve;
        rejectHandler = requireNonNull(defaultRejectHandler, "defaultRejectHandler");
    }

    final ThrottlingStrategy<I> getStrategy() {
        return strategy;
    }

    /**
     * Sets {@link ThrottlingAcceptHandler}.
     */
    final void setAcceptHandler(
            ThrottlingAcceptHandler<I, O> acceptHandler) {
        this.acceptHandler = requireNonNull(acceptHandler, "acceptHandler");
    }

    final ThrottlingAcceptHandler<I, O> getAcceptHandler() {
        return acceptHandler;
    }

    /**
     * Sets {@link ThrottlingRejectHandler}.
     */
    final void setRejectHandler(
            ThrottlingRejectHandler<I, O> rejectHandler) {
        this.rejectHandler = requireNonNull(rejectHandler, "rejectHandler");
    }

    final ThrottlingRejectHandler<I, O> getRejectHandler() {
        return rejectHandler;
    }
}
