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

final class Resilience4jCircuitBreakerUtil {

    static final Resilience4jCircuitBreakerFactory FACTORY = (registry, host, method, path) -> {
        String key = "";
        if (host != null) {
            key = host;
        }
        if (method != null) {
            if (!key.isEmpty()) {
                key += '#';
            }
            key += method;
        }
        if (path != null) {
            if (!key.isEmpty()) {
                key += '#';
            }
            key += path;
        }
        return registry.circuitBreaker(key);
    };

    private Resilience4jCircuitBreakerUtil() {}
}
