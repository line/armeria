/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.internal.consul;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;

class ConsulClientTest extends ConsulTestBase {

    @Test
    void testGet() {
        final AggregatedHttpResponse response = client().consulWebClient()
                                                        .get("catalog/nodes").aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().length()).isGreaterThan(10);
    }

    @Test
    void testPut() {
        final String payload = '{' +
                               " \"Node\": \"foobar\"," +
                               " \"Address\": \"127.0.0.1\"," +
                               " \"Service\" : {\"Service\":\"redis\"} " +
                               '}';
        final AggregatedHttpResponse resPut = client().consulWebClient()
                                                      .put("catalog/register", payload).aggregate().join();
        assertThat(resPut.status()).isEqualTo(HttpStatus.OK);
        assertThat(resPut.contentUtf8().trim()).isEqualTo("true");

        final AggregatedHttpResponse resGet = client().consulWebClient()
                                                      .get("catalog/service/redis").aggregate().join();
        assertThat(resGet.status()).isEqualTo(HttpStatus.OK);
        assertThat(resGet.contentUtf8()).contains("redis");
    }

    @Test
    void testRegisterAndDeregister() {
        final Endpoint endpoint = Endpoint.of("localhost", 8080);
        final String serviceId = serviceName + '_' + endpoint.host() + '_' + endpoint.port();
        client().register(serviceId, serviceName, endpoint, null).aggregate().join();
        AggregatedHttpResponse resGet = client().consulWebClient()
                                                .get("catalog/service/" + serviceName).aggregate().join();

        assertThat(resGet.status()).isEqualTo(HttpStatus.OK);
        assertThat(resGet.contentUtf8()).contains(serviceName);

        client().deregister(serviceId).aggregate().join();

        resGet = client().consulWebClient()
                         .get("catalog/service/" + serviceName).aggregate().join();
        assertThat(resGet.status()).isEqualTo(HttpStatus.OK);
        assertThat(resGet.contentUtf8()).doesNotContain(serviceId);
    }
}
