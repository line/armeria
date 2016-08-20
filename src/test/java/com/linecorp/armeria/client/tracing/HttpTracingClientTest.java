/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client.tracing;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.Test;

import com.github.kristofa.brave.Brave;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DefaultClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.DefaultHttpRequest;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.tracing.HttpTracingTestBase;
import com.linecorp.armeria.service.test.thrift.main.HelloService;

import io.netty.channel.DefaultEventLoop;

public class HttpTracingClientTest extends HttpTracingTestBase {

    @SuppressWarnings("unchecked")
    private static final HttpTracingClient client =
            new HttpTracingClient(mock(Client.class), mock(Brave.class));

    @Test
    public void testPutTraceData() {
        final HttpRequest req = newRequest();
        final ClientRequestContext ctx = newClientContext(req);

        ctx.attr(ClientRequestContext.HTTP_HEADERS).set(otherHeaders());

        client.putTraceData(ctx, req, testSpanId);

        HttpHeaders expectedHeaders = traceHeaders().add(otherHeaders());
        assertThat(ctx.attr(ClientRequestContext.HTTP_HEADERS).get(), is(expectedHeaders));
    }

    @Test
    public void testPutTraceDataIfSpanIsNull() {
        final HttpRequest req = newRequest();
        final ClientRequestContext ctx = newClientContext(req);

        client.putTraceData(ctx, req, null);

        HttpHeaders expectedHeaders = traceHeadersNotSampled();
        assertThat(ctx.attr(ClientRequestContext.HTTP_HEADERS).get(), is(expectedHeaders));
    }

    private static HttpRequest newRequest() {
        final DefaultHttpRequest req = new DefaultHttpRequest(HttpMethod.POST, "/hello");
        req.close();
        return req;
    }

    private static ClientRequestContext newClientContext(HttpRequest req) {
        return new DefaultClientRequestContext(
                new DefaultEventLoop(), SessionProtocol.H2C, Endpoint.of("localhost", 8080),
                req.method().toString(), req.path(), ClientOptions.DEFAULT, req);
    }
}
