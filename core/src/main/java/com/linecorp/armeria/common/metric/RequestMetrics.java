/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.metric;

/**
 * A {@link MetricGroup} of request stats.
 */
public interface RequestMetrics extends MetricGroup {

    /**
     * Returns the number of requests currently being handled.
     */
    Gauge active();

    /**
     * Returns the number of handled requests.
     */
    Gauge total();

    /**
     * Returns the number of successfully handled requests.
     */
    Gauge success();

    /**
     * Returns the number of non-successfully handled requests.
     */
    Gauge failure();

    /**
     * Returns the amount of time took to produce/consume a request.
     */
    Histogram requestDuration();

    /**
     * Returns the number of bytes in a request.
     */
    Histogram requestLength();

    /**
     * Returns the amount of time took to produce/consume a response.
     */
    Histogram responseDuration();

    /**
     * Returns the number of bytes in a response.
     */
    Histogram responseLength();

    /**
     * Returns the amount of time took to handle a request.
     */
    Histogram totalDuration();
}
