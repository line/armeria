/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.metric;

import io.prometheus.client.Collector;

/**
 * User definable label name for metrics (ex. {@link Collector}).
 *
 * <p>Example:
 * <pre>{@code
 * public enum MyMetricLabel implements MetricLabel<MyMetricLabel> {
 *     path,
 *     handler,
 *     method,
 * }}</pre>
 *
 * @param <T> the implementing class
 */
@FunctionalInterface
public interface MetricLabel<T extends MetricLabel<T>> extends Comparable<T> {
    /**
     * Returns a single label name.
     * @return label name
     */
    String name();

    @Override
    default int compareTo(T o) {
        return name().compareTo(o.name());
    }
}
