/*
 * Copyright 2018 LINE Corporation
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

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.metric.MetricCollectingService;

/**
 * A {@link Service} that handles transient requests, for example, health check requests.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
@FunctionalInterface
public interface TransientService<I extends Request, O extends Response> extends Service<I, O> {

    /**
     * Tells whether the specified {@link ActionType} is enabled for this {@link TransientService}.
     */
    default boolean countFor(ActionType type) {
        return false;
    }

    /**
     * The type of actions that is used in {@link TransientService#countFor(ActionType)}.
     */
    enum ActionType {

        /**
         * Graceful shutdown counts the requests to the {@link TransientService} as processing requests.
         */
        GRACEFUL_SHUTDOWN,

        /**
         * {@link MetricCollectingService} collects the metrics of the requests to the {@link TransientService}.
         */
        METRIC_COLLECTION,

        /**
         * {@link LoggingService} logs the requests to the {@link TransientService}.
         */
        LOGGING,

        /**
         * {@link AccessLogWriter} produces the access logs of the requests to the {@link TransientService}.
         */
        ACCESS_LOGGING;
    }
}
