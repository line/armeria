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
 * A metric.
 */
public interface Metric {
    /**
     * Returns the {@link MetricKey} of this metric. If this metric is registered to {@link Metrics},
     * it can be retrieved using {@link Metrics#metric(MetricKey, Class)}.
     */
    MetricKey key();

    /**
     * Returns the {@link MetricUnit} of this metric.
     */
    MetricUnit unit();

    /**
     * Returns the human-readable description of this metric.
     */
    String description();
}
