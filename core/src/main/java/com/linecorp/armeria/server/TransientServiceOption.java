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

import java.util.Set;

import com.google.common.collect.Sets;

import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.metric.MetricCollectingService;

/**
 * Specifies which features should be enabled for a {@link TransientService}.
 * For example, if you do:
 * <pre>{@code
 * ServerBuilder sb = Server.builder();
 * sb.service("/health", HealthCheckService.builder() // A TransientService
 *                                         .transientServiceOptions(
 *                                                 TransientServiceOption.WITH_METRIC_COLLECTION,
 *                                                 TransientServiceOption.WITH_ACCESS_LOGGING)
 *                                         .build());
 * }</pre>
 * then, the metric is collected by {@link MetricCollectingService} and access logs are produced by
 * {@link AccessLogWriter} for the {@link HealthCheckService}.
 */
public enum TransientServiceOption {

    /**
     * Enables {@link MetricCollectingService} to collect the metrics of the requests to the
     * {@link TransientService}.
     */
    WITH_METRIC_COLLECTION,

    /**
     * Enables {@link LoggingService} to log the requests to the {@link TransientService}.
     */
    WITH_LOGGING,

    /**
     * Enables {@link AccessLogWriter} to produce the access logs of the requests to the
     * {@link TransientService}.
     */
    WITH_ACCESS_LOGGING;

    private static final Set<TransientServiceOption> allOf = Sets.immutableEnumSet(WITH_METRIC_COLLECTION,
                                                                                   WITH_LOGGING,
                                                                                   WITH_ACCESS_LOGGING);

    /**
     * Returns all {@link TransientServiceOption}s.
     */
    public static Set<TransientServiceOption> allOf() {
        return allOf;
    }
}
