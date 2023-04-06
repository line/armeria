/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.server.tomcat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.catalina.Service;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.AppRootFinder;
import com.linecorp.armeria.internal.testing.webapp.WebAppContainerTest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.util.concurrent.Future;

class ManagedTomcatServiceTest extends WebAppContainerTest {

    private static final String SERVICE_NAME = "TomcatServiceTest";

    private static final List<Service> tomcatServices = new ArrayList<>();

    private static final RuntimeException RUNTIME_EXCEPTION = new RuntimeException();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();

            sb.serviceUnder(
                    "/jsp/",
                    TomcatService.builder(webAppRoot())
                                 .serviceName(SERVICE_NAME)
                                 .configurator(s -> Collections.addAll(tomcatServices, s.findServices()))
                                 .build()
                                 .decorate(LoggingService.newDecorator()));

            sb.serviceUnder(
                    "/jar/",
                    TomcatService.builder(AppRootFinder.find(Future.class))
                                 .serviceName("TomcatServiceTest-JAR")
                                 .build()
                                 .decorate(LoggingService.newDecorator()));

            sb.serviceUnder(
                    "/jar_altroot/",
                    TomcatService.builder(AppRootFinder.find(Future.class), "/io/netty/util/concurrent")
                                 .serviceName("TomcatServiceTest-JAR-AltRoot")
                                 .build()
                                 .decorate(LoggingService.newDecorator()));

            sb.service("/throwing",
                       TomcatService.builder(webAppRoot())
                                    .build()
                                    .decorate((delegate, ctx, req) -> {
                                        ctx = spy(ctx);
                                        // relies on the fact that TomcatService calls this method
                                        when(ctx.sessionProtocol()).thenThrow(RUNTIME_EXCEPTION);
                                        return delegate.serve(ctx, req);
                                    }));
        }
    };

    @Override
    protected ServerExtension server() {
        return server;
    }

    @Test
    void configurator() throws Exception {
        assertThat(tomcatServices).hasSize(1);
        assertThat(tomcatServices.get(0).getName()).isEqualTo(SERVICE_NAME);
    }

    @Test
    void jarBasedWebApp() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(
                    new HttpGet(server.httpUri() + "/jar/io/netty/util/concurrent/Future.class"))) {
                assertThat(res.getCode()).isEqualTo(200);
                assertThat(res.getFirstHeader(HttpHeaderNames.CONTENT_TYPE.toString()).getValue())
                        .startsWith("application/java");
                assertThat(res.getFirstHeader(HttpHeaderNames.CONTENT_LENGTH.toString()).getValue())
                        .isEqualTo("1361");
                EntityUtils.consume(res.getEntity());
            }
        }
    }

    @Test
    void jarBasedWebAppWithAlternativeRoot() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(
                    server.httpUri() + "/jar_altroot/Future.class"))) {
                assertThat(res.getCode()).isEqualTo(200);
                assertThat(res.getFirstHeader(HttpHeaderNames.CONTENT_TYPE.toString()).getValue())
                        .startsWith("application/java");
                assertThat(res.getFirstHeader(HttpHeaderNames.CONTENT_LENGTH.toString()).getValue())
                        .isEqualTo("1361");
                EntityUtils.consume(res.getEntity());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C"})
    void throwingExceptionIsLogged(SessionProtocol sessionProtocol) throws Exception {
        final AggregatedHttpResponse res = WebClient.builder(sessionProtocol, server.httpEndpoint())
                                                    .build().blocking().get("/throwing");
        assertThat(res.status().code()).isEqualTo(500);

        assertThat(server.requestContextCaptor().size()).isEqualTo(1);
        final ServiceRequestContext sctx = server.requestContextCaptor().poll();
        await().atMost(10, TimeUnit.SECONDS).until(() -> sctx.log().isComplete());
        assertThat(sctx.log().ensureComplete().responseCause())
                .isInstanceOf(IllegalStateException.class)
                .hasCause(RUNTIME_EXCEPTION);
    }
}
