/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.common.metrics;

import java.util.Objects;
import java.util.Optional;

import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;

@FunctionalInterface
public interface MetricConsumer {
    /**
     * Invoked for each request that has been processed
     *
     * @param scheme the {@link Scheme} which the invocation has been performed on.
     * @param code the {@link SessionProtocol}-specific status code that signifies the result of the
     *             invocation. e.g. HTTP response status code
     * @param processTimeNanos elapsed nano time processing request
     * @param requestSize number of bytes in request if possible, otherwise it will be 0
     * @param responseSize number of bytes in response if possible, otherwise it will be 0
     */
    void invocationComplete(Scheme scheme, int code, long processTimeNanos, int requestSize,
                            int responseSize, String hostname, String path, Optional<String> method);

    default MetricConsumer andThen(MetricConsumer other) {
        Objects.requireNonNull(other, "other");
        return (scheme, code, processNanoTime, requestSize, responseSize, hostname, path,
                mappedPath) -> {
            try {
                invocationComplete(scheme, code, processNanoTime, requestSize, responseSize,
                                   hostname, path, mappedPath);
            } catch (Throwable e) {
                LoggerFactory.getLogger(MetricConsumer.class).warn(
                        "invocationComplete() failed with an exception: {}", e);
            }
            other.invocationComplete(scheme, code, processNanoTime, requestSize, responseSize,
                                     hostname, path, mappedPath);
        };
    }
}

