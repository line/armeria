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

package com.linecorp.armeria.client.circuitbreaker;

/**
 * A functional interface that represents a mapper factory, mapping a combination of host, method and path
 * to a CircuitBreaker.
 */
@FunctionalInterface
interface CircuitBreakerFactory {
    /**
     * Given a combination of host, method and path, creates a CircuitBreaker.
     * @param host the host of the context endpoint.
     * @param method the method of the context request.
     * @param path the path of the context request.
     * @return the CircuitBreaker instance corresponding to this combination.
     */
    CircuitBreaker apply(String host, String method, String path);
}
