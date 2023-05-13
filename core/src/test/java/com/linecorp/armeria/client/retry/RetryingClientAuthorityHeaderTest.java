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

package com.linecorp.armeria.client.retry;

import static com.linecorp.armeria.common.HttpStatus.SERVICE_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.testing.server.ServiceRequestContextCaptor;

class RetryingClientAuthorityHeaderTest {

    @RegisterExtension
    static ServerExtension backend1 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return HttpResponse.of(SERVICE_UNAVAILABLE);
                }
            });
            sb.decorator(LoggingService.newDecorator());
        }
    };

    @RegisterExtension
    static ServerExtension backend2 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    if (backend1.requestContextCaptor().size() == 0) {
                        // ensure that at least one call has been made to backend1
                        return HttpResponse.of(SERVICE_UNAVAILABLE);
                    }
                    return HttpResponse.of(req.headers().authority());
                }
            });
            sb.decorator(LoggingService.newDecorator());
        }
    };

    @AfterEach
    void beforeEach() {
        assertThat(backend1.requestContextCaptor().size()).isEqualTo(0);
        assertThat(backend2.requestContextCaptor().size()).isEqualTo(0);
    }

    @Test
    void authorityIsDifferentByBackendsWhenRetry() throws Exception {
        final WebClient client = newHttpClientWithEndpointGroup();

        final AggregatedHttpResponse res = client.get("/").aggregate().join();
        // if no header is set, the endpoint host is used
        assertThat(res.contentUtf8()).contains("www.bar.com");

        assertThat(backend1.requestContextCaptor().size()).isGreaterThanOrEqualTo(1);
        assertCaptorMatches(backend1.requestContextCaptor(), ctx -> {
            assertThat(((InetSocketAddress) ctx.localAddress()).getPort()).isEqualTo(backend1.httpPort());
            assertThat(ctx.request().uri().getPort()).isEqualTo(backend1.httpPort());
        });

        assertThat(backend2.requestContextCaptor().size()).isGreaterThanOrEqualTo(1);
        assertCaptorMatches(backend2.requestContextCaptor(), ctx -> {
            assertThat(((InetSocketAddress) ctx.localAddress()).getPort()).isEqualTo(backend2.httpPort());
            assertThat(ctx.request().uri().getPort()).isEqualTo(backend2.httpPort());
        });
    }

    @Test
    void requestAuthorityIsPreservedForRetries() throws Exception {
        final WebClient client = newHttpClientWithEndpointGroup();

        final AggregatedHttpResponse res =
                client.execute(HttpRequest.of(
                        RequestHeaders.of(HttpMethod.GET, "/",
                                          HttpHeaderNames.AUTHORITY, "hostname.com:8080"))).aggregate().join();
        // if a header is set, the endpoint host is used
        assertThat(res.contentUtf8()).contains("hostname.com:8080");
        assertThat(backend1.requestContextCaptor().size()).isGreaterThanOrEqualTo(1);
        assertCaptorMatches(backend1.requestContextCaptor(), ctx -> {
            assertThat(ctx.request().authority()).isEqualTo("hostname.com:8080");
        });
        assertThat(backend2.requestContextCaptor().size()).isGreaterThanOrEqualTo(1);
        assertCaptorMatches(backend2.requestContextCaptor(), ctx -> {
            assertThat(ctx.request().authority()).isEqualTo("hostname.com:8080");
        });
    }

    @Test
    void clientAuthorityIsPreservedForRetries() throws Exception {
        final WebClient client = newHttpClientWithEndpointGroupBuilder()
                .setHeader(HttpHeaderNames.AUTHORITY, "client.authority:1234")
                .build();

        final AggregatedHttpResponse res =
                client.execute(HttpRequest.of(
                        RequestHeaders.of(HttpMethod.GET, "/"))).aggregate().join();
        // if a header is set, the endpoint host is used
        assertThat(res.contentUtf8()).contains("client.authority:1234");
        assertThat(backend1.requestContextCaptor().size()).isGreaterThanOrEqualTo(1);
        assertCaptorMatches(backend1.requestContextCaptor(), ctx -> {
            assertThat(ctx.request().authority()).isEqualTo("client.authority:1234");
        });
        assertThat(backend2.requestContextCaptor().size()).isGreaterThanOrEqualTo(1);
        assertCaptorMatches(backend2.requestContextCaptor(), ctx -> {
            assertThat(ctx.request().authority()).isEqualTo("client.authority:1234");
        });
    }

    private static WebClient newHttpClientWithEndpointGroup() {
        return newHttpClientWithEndpointGroupBuilder().build();
    }

    private static WebClientBuilder newHttpClientWithEndpointGroupBuilder() {
        final EndpointGroup endpointGroup = EndpointGroup.of(
                Endpoint.of("www.foo.com", backend1.httpPort()).withIpAddr("127.0.0.1"),
                Endpoint.of("www.bar.com", backend2.httpPort()).withIpAddr("127.0.0.1"));
        return WebClient.builder(SessionProtocol.H2C, endpointGroup)
                        .decorator(RetryingClient.newDecorator(
                                RetryRule.builder().onServerErrorStatus().onException().thenBackoff()));
    }

    private static void assertCaptorMatches(ServiceRequestContextCaptor captor,
                                            Consumer<ServiceRequestContext> consumer) throws Exception {
        while (captor.size() > 0) {
            final ServiceRequestContext ctx = captor.poll();
            consumer.accept(ctx);
        }
    }
}
