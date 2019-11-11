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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.consul.ConsulTestBase;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;

class ConsulClientTest extends ConsulTestBase {

    @Test
    void testGet() {
        final AggregatedHttpResponse response = client().consulWebClient()
                                                        .get("catalog/nodes").aggregate().join();
        assertEquals(HttpStatus.OK, response.status(), "response status code should be:");
        assertTrue(10 < response.content().length(), "response body length should be:");
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
        assertEquals(HttpStatus.OK, resPut.status(), "response status code should be:");
        assertEquals("true", resPut.contentUtf8().trim(), "result of registration should be:");
        final AggregatedHttpResponse resGet = client().consulWebClient()
                                                      .get("catalog/service/redis").aggregate().join();
        assertEquals(HttpStatus.OK, resGet.status(), "response status code should be:");
        assertTrue(resGet.contentUtf8().contains("redis"), "result should contain 'redis'");
    }

    @Test
    void testRegisterAndDeregister() throws JsonProcessingException {
        final Endpoint endpoint = Endpoint.of("localhost", 8080);
        final String serviceId = serviceName + '_' + endpoint.host() + '_' + endpoint.port();
        final String result = client().register(serviceId, serviceName, endpoint, null).join();
        System.out.println(result);
        AggregatedHttpResponse resGet = client().consulWebClient()
                                                .get("catalog/service/" + serviceName).aggregate().join();
        System.out.println(resGet.contentUtf8());
        assertEquals(HttpStatus.OK, resGet.status(), "response status code should be:");
        assertTrue(resGet.contentUtf8().contains(serviceName), "result should contain '" + serviceName + '\'');

        client().deregister(serviceId).join();

        resGet = client().consulWebClient()
                         .get("catalog/service/" + serviceName).aggregate().join();
        assertEquals(HttpStatus.OK, resGet.status(), "response status code should be:");
        assertFalse(resGet.contentUtf8().contains(serviceId), "result should not contain '" + serviceId + '\'');
    }
}
