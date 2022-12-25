/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.resilience4j.circuitbreaker;

import static java.util.stream.Collectors.joining;

import java.util.Objects;
import java.util.stream.Stream;

final class Resilience4jCircuitBreakerUtils {

    static final Resilience4jCircuitBreakerFactory FACTORY = (registry, host, method, path) -> {
        final String key = Stream.of(host, method, path)
                                 .filter(Objects::nonNull)
                                 .collect(joining("#"));
        return registry.circuitBreaker(key);
    };

    private Resilience4jCircuitBreakerUtils() {}
}
