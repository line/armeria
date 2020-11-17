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
package com.linecorp.armeria.server;

import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.metric.MetricCollectingService;

/**
 * Specifies which features should be disabled for a {@link TransientService}.
 * For example, if you do:
 * <pre>{@code
 * ServerBuilder sb = Server.builder();
 * sb.service("/health", HealthCheckService.builder() // A TransientService
 *                                         .optOutFeatures(OptOutFeature.GRACEFUL_SHUTDOWN,
 *                                                         OptOutFeature.ACCESS_LOGGING,
 *                                                         OptOutFeature.METRIC_COLLECTION)
 *                                         .build());
 * }</pre>
 * then, every feature is opted out for {@link HealthCheckService} except logging using {@link LoggingService}.
 */
public enum OptOutFeature {

    /**
     * Prevents a {@link Server} from counting the requests to the {@link TransientService}
     * as processing requests for graceful shutdown.
     *
     * @see ServerBuilder#gracefulShutdownTimeoutMillis(long, long)
     */
    GRACEFUL_SHUTDOWN,

    /**
     * Prevents {@link MetricCollectingService} from collecting the metrics of the requests to the
     * {@link TransientService}.
     */
    METRIC_COLLECTION,

    /**
     * Prevents {@link LoggingService} from logging the requests to the {@link TransientService}.
     */
    LOGGING,

    /**
     * Prevents {@link AccessLogWriter} from producing the access logs of the requests to the
     * {@link TransientService}.
     */
    ACCESS_LOGGING;
}
