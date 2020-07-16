/*
 * Copyright 2018 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;

class KeyedCircuitBreakerMappingTest {

    private static final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");

    @Test
    void hostSelector() throws Exception {
        final CircuitBreaker a = mock(CircuitBreaker.class);
        final CircuitBreaker b = mock(CircuitBreaker.class);
        final CircuitBreaker c = mock(CircuitBreaker.class);
        final CircuitBreaker d = mock(CircuitBreaker.class);
        final CircuitBreaker e = mock(CircuitBreaker.class);
        final Function<String, ? extends CircuitBreaker> factory = host -> {
            if ("foo".equals(host)) {
                return a;
            }
            if ("foo:8080".equals(host)) {
                return b;
            }
            if ("foo/1.2.3.4".equals(host)) {
                return c;
            }
            if ("1.2.3.4:80".equals(host)) {
                return d;
            }
            if ("[::1]:80".equals(host)) {
                return e;
            }
            return null;
        };

        final CircuitBreakerMapping breakerMapping = CircuitBreakerMapping.perHost(factory);
        assertThat(breakerMapping.get(context(Endpoint.of("foo")), req)).isSameAs(a);
        assertThat(breakerMapping.get(context(Endpoint.of("foo", 8080)), req)).isSameAs(b);
        assertThat(breakerMapping.get(context(Endpoint.of("foo").withIpAddr("1.2.3.4")), req)).isSameAs(c);
        assertThat(breakerMapping.get(context(Endpoint.of("1.2.3.4", 80)), req)).isSameAs(d);
        assertThat(breakerMapping.get(context(Endpoint.of("::1", 80)), req)).isSameAs(e);

        assertThat(breakerMapping.get(context(Endpoint.of("bar")), req)).isNull();
    }

    private static ClientRequestContext context(Endpoint endpoint) {
        return ClientRequestContext.builder(req)
                                   .endpoint(endpoint)
                                   .build();
    }
}
