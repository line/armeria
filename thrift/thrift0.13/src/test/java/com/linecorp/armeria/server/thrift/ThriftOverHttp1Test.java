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

import java.security.GeneralSecurityException;

import javax.net.ssl.SSLContext;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.thrift.transport.THttpClient;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.junit.Ignore;
import org.junit.Test;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;

import io.netty.util.AsciiString;
import testing.thrift.main.SleepService;

public class ThriftOverHttp1Test extends AbstractThriftOverHttpTest {
    @Override
    protected TTransport newTransport(String uri, HttpHeaders headers) throws TTransportException {
        final SSLContext sslContext;
        try {
            sslContext = SSLContextBuilder.create()
                                          .loadTrustMaterial((TrustStrategy) (chain, authType) -> true)
                                          .build();
        } catch (GeneralSecurityException e) {
            throw new TTransportException("failed to initialize an SSL context", e);
        }

        final THttpClient client = new THttpClient(
                uri, HttpClientBuilder.create()
                                      .setSSLHostnameVerifier((hostname, session) -> true)
                                      .setSSLContext(sslContext)
                                      .build());
        client.setCustomHeaders(
                headers.names().stream()
                       .collect(toImmutableMap(AsciiString::toString,
                                               name -> String.join(", ", headers.getAll(name)))));
        return client;
    }

    @Test
    public void testNonPostRequest() throws Exception {
        final HttpUriRequest[] reqs = {
                new HttpGet(newUri("http", "/hello")),
                new HttpDelete(newUri("http", "/hello"))
        };

        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            for (HttpUriRequest r: reqs) {
                try (CloseableHttpResponse res = hc.execute(r)) {
                    assertThat(res.getStatusLine().toString()).isEqualTo(
                            "HTTP/1.1 405 Method Not Allowed");
                    assertThat(EntityUtils.toString(res.getEntity()))
                              .isNotEqualTo("Hello, world!");
                }
            }
        }
    }

    @Test
    @Ignore
    public void testPipelinedHttpInvocation() throws Exception {
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
