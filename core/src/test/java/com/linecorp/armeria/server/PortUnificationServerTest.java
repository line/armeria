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

package com.linecorp.armeria.server;

import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static com.linecorp.armeria.common.SessionProtocol.HTTPS;
import static com.linecorp.armeria.common.SessionProtocol.PROXY;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;

import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.testing.server.ServerRule;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

@RunWith(Parameterized.class)
public class PortUnificationServerTest {

    private static final ClientFactory clientFactory =
            new ClientFactoryBuilder().sslContextCustomizer(
                    b -> b.trustManager(InsecureTrustManagerFactory.INSTANCE)).build();

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.port(0, PROXY, HTTP, HTTPS);
            sb.tlsSelfSigned();
            sb.service("/", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                                           ctx.sessionProtocol().name());
                }
            });
        }
    };

    @Parameters(name = "{index}: scheme={0}")
    public static Collection<String> schemes() {
        return ImmutableList.of("h1c", "h2c", "h1", "h2");
    }

    private final String scheme;

    public PortUnificationServerTest(String scheme) {
        this.scheme = scheme;
    }

    @Test
    public void httpAndHttpsUsesSamePort() {
        assertThat(server.httpPort()).isEqualTo(server.httpsPort());
    }

    @Test
    public void test() throws Exception {
        final HttpClient client = HttpClient.of(clientFactory,
                                                scheme + "://127.0.0.1:" + server.httpsPort() + '/');
        final AggregatedHttpMessage response = client.execute(HttpRequest.of(HttpMethod.GET, "/"))
                                                     .aggregate().join();
        assertThat(response.contentUtf8()).isEqualToIgnoringCase(scheme);
    }
}
