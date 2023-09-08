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

package com.linecorp.armeria.resilience4j.circuitbreaker.client;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.assertj.core.util.Maps;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

class Resilience4jCircuitBreakerMappingTest {

    @Test
    void testInvalidMapping() {
        assertThatThrownBy(() -> Resilience4jCircuitBreakerMapping.builder().build())
                .withFailMessage("A Resilience4jCircuitBreakerMapping must be per host, method and/or path")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testMappingKey() {
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext ctx = ClientRequestContext.of(request);

        final Resilience4jCircuitBreakerMapping perPath =
                Resilience4jCircuitBreakerMapping.builder().perPath().build();
        assertThat(perPath.get(ctx, request).getName()).isEqualTo("/");

        final Resilience4jCircuitBreakerMapping perMethod =
                Resilience4jCircuitBreakerMapping.builder().perMethod().build();
        assertThat(perMethod.get(ctx, request).getName()).isEqualTo("GET");

        final Resilience4jCircuitBreakerMapping perPathMethod =
                Resilience4jCircuitBreakerMapping.builder().perMethod().perPath().build();
        assertThat(perPathMethod.get(ctx, request).getName()).isEqualTo("GET#/");
    }

    @Test
    void registryShareScope() {
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext ctx = ClientRequestContext.of(request);

        final Resilience4jCircuitBreakerMapping mapping1 = Resilience4jCircuitBreakerMapping.perHost();
        final Resilience4jCircuitBreakerMapping mapping2 = Resilience4jCircuitBreakerMapping.perHost();

        // the key is the same, but each mapping creates its own registry producing different instances
        assertThat(mapping1.get(ctx, request).getName()).isEqualTo(mapping2.get(ctx, request).getName());
        assertThat(mapping1.get(ctx, request)).isNotSameAs(mapping2.get(ctx, request));

        final Resilience4jCircuitBreakerMapping mapping3 = Resilience4jCircuitBreakerMapping.of();
        final Resilience4jCircuitBreakerMapping mapping4 = Resilience4jCircuitBreakerMapping.of();

        // the default mapping shares registries
        assertThat(mapping3.get(ctx, request).getName()).isEqualTo(mapping4.get(ctx, request).getName());
        assertThat(mapping3.get(ctx, request)).isSameAs(mapping4.get(ctx, request));
    }

    @Test
    void factoryMethod() {
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext ctx = ClientRequestContext.of(request);

        final Map<String, String> tags = Maps.newHashMap("a", "b");
        final Resilience4jCircuitBreakerMapping mapping = Resilience4jCircuitBreakerMapping
                .builder()
                .factory((reg, host, method, path) -> reg.circuitBreaker(requireNonNull(host), tags))
                .perHost()
                .build();
        final CircuitBreaker cb = mapping.get(ctx, request);
        assertThat(cb.getTags()).containsExactly(Map.entry("a", "b"));
    }
}
