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

import static com.linecorp.armeria.client.circuitbreaker.KeyedCircuitBreakerMapping.KeySelector.HOST;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextBuilder;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;

public class KeyedCircuitBreakerMappingTest {
    @Test
    public void hostSelector() throws Exception {
        assertThat(HOST.get(context(Endpoint.ofGroup("foo")), null)).isEqualTo("group:foo");
        assertThat(HOST.get(context(Endpoint.of("foo")), null)).isEqualTo("foo");
        assertThat(HOST.get(context(Endpoint.of("foo", 8080)), null)).isEqualTo("foo:8080");
        assertThat(HOST.get(context(Endpoint.of("foo").withIpAddr("1.2.3.4")), null)).isEqualTo("foo/1.2.3.4");
        assertThat(HOST.get(context(Endpoint.of("1.2.3.4", 80)), null)).isEqualTo("1.2.3.4:80");
        assertThat(HOST.get(context(Endpoint.of("::1", 80)), null)).isEqualTo("[::1]:80");
    }

    private static ClientRequestContext context(Endpoint endpoint) {
        return ClientRequestContextBuilder.of(HttpRequest.of(HttpMethod.GET, "/"))
                                          .endpoint(endpoint)
                                          .build();
    }
}
