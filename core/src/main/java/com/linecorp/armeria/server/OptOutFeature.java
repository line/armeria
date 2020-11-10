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

import java.util.EnumSet;
import java.util.Set;

import com.google.common.collect.Sets;

import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.metric.MetricCollectingService;

/**
 * The opt-out features that are disabled for a {@link TransientService}.
 */
public enum OptOutFeature {

    /**
     * Whether graceful shutdown counts the requests to the {@link TransientService} as processing requests.
     *
     * @see ServerBuilder#gracefulShutdownTimeoutMillis(long, long)
     */
    GRACEFUL_SHUTDOWN,

    /**
     * Whether {@link MetricCollectingService} collects the metrics of the requests to the
     * {@link TransientService}.
     */
    METRIC_COLLECTION,

    /**
     * Whether {@link LoggingService} logs the requests to the {@link TransientService}.
     */
    LOGGING,

    /**
     * Whether {@link AccessLogWriter} produces the access logs of the requests to the
     * {@link TransientService}.
     */
    ACCESS_LOGGING;

    private static final Set<OptOutFeature> allOf = Sets.immutableEnumSet(EnumSet.allOf(OptOutFeature.class));

    /**
     * Returns the default {@link OptOutFeature}s.
     */
    public static Set<OptOutFeature> defaultOptOutFeatures() {
        return allOf;
    }
}
