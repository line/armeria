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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.catalina.Service;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.util.AppRootFinder;
import com.linecorp.armeria.internal.testing.webapp.WebAppContainerTest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.util.concurrent.Future;

class ManagedTomcatServiceTest extends WebAppContainerTest {

    private static final String SERVICE_NAME = "TomcatServiceTest";

    private static final List<Service> tomcatServices = new ArrayList<>();

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
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
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
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(res.getFirstHeader(HttpHeaderNames.CONTENT_TYPE.toString()).getValue())
                        .startsWith("application/java");
                assertThat(res.getFirstHeader(HttpHeaderNames.CONTENT_LENGTH.toString()).getValue())
                        .isEqualTo("1361");
                EntityUtils.consume(res.getEntity());
            }
        }
    }
}
