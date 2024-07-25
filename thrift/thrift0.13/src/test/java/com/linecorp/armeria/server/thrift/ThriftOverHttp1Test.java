/*
 * Copyright 2015 LINE Corporation
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
package com.linecorp.armeria.server.thrift;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static org.assertj.core.api.Assertions.assertThat;

import org.apache.thrift.transport.THttpClient;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.internal.ApacheClientUtils;

import io.netty.util.AsciiString;
import testing.thrift.main.SleepService;

class ThriftOverHttp1Test extends AbstractThriftOverHttpTest {

    @Override
    protected TTransport newTransport(String uri, HttpHeaders headers) throws TTransportException {
        final THttpClient client = ApacheClientUtils.allTrustClient(uri);
        client.setCustomHeaders(
                headers.names().stream()
                       .collect(toImmutableMap(AsciiString::toString,
                                               name -> String.join(", ", headers.getAll(name)))));
        return client;
    }

    @ParameterizedTest
    @EnumSource(value = HttpMethod.class, names = {"DELETE", "GET"})
    void testNonPostRequest(HttpMethod method) throws Exception {
        final AggregatedHttpResponse res =
                WebClient.of(server.uri(SessionProtocol.H1C, SerializationFormat.NONE))
                         .blocking()
                         .execute(HttpRequest.of(method, "/hello"));
        assertThat(res.status().code()).isEqualTo(405);
        assertThat(res.contentUtf8()).doesNotContain("Hello, world!");
    }

    @Test
    @Disabled
    void testPipelinedHttpInvocation() throws Exception {
        // FIXME: Enable this test once we have a working Thrift-over-HTTP/1 client with pipelining.
        try (TTransport transport = newTransport("http", "/sleep")) {
            final SleepService.Client client = new SleepService.Client.Factory().getClient(
                    ThriftProtocolFactories.binary(0, 0).getProtocol(transport));

            client.send_sleep(1000);
            client.send_sleep(500);
            client.send_sleep(0);
            assertThat(client.recv_sleep()).isEqualTo(1000L);
            assertThat(client.recv_sleep()).isEqualTo(500L);
            assertThat(client.recv_sleep()).isEqualTo(0L);
        }
    }
}
