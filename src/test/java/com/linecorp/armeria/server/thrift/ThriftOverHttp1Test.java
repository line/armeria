/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server.thrift;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import javax.net.ssl.SSLContext;

import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
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

import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.service.test.thrift.main.SleepService;

public class ThriftOverHttp1Test extends AbstractThriftOverHttpTest {

    private final HttpClient httpClient;

    public ThriftOverHttp1Test() {
        try {
            SSLContext sslCtx =
                    SSLContextBuilder.create().loadTrustMaterial(TrustSelfSignedStrategy.INSTANCE).build();

            httpClient = HttpClientBuilder.create().setSSLContext(sslCtx).build();
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    @Override
    protected TTransport newTransport(String uri) throws TTransportException {
        return new THttpClient(uri, httpClient);
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
                    assertThat(res.getStatusLine().toString(), is("HTTP/1.1 405 Method Not Allowed"));
                    assertThat(EntityUtils.toString(res.getEntity()), is(not("Hello, world!")));
                }
            }
        }
    }

    @Test
    @Ignore
    public void testPipelinedHttpInvocation() throws Exception {
        // FIXME: Enable this test once we have a working Thrift-over-HTTP/1 client with pipelining.
        try (TTransport transport = newTransport("http", "/sleep")) {
            SleepService.Client client = new SleepService.Client.Factory().getClient(
                    ThriftProtocolFactories.BINARY.getProtocol(transport));

            client.send_sleep(1000);
            client.send_sleep(500);
            client.send_sleep(0);
            assertThat(client.recv_sleep(), is(1000L));
            assertThat(client.recv_sleep(), is(500L));
            assertThat(client.recv_sleep(), is(0L));
        }
    }
}
