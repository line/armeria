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
package com.linecorp.armeria.server.http.tomcat;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.catalina.Service;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.Test;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.http.WebAppContainerTest;
import com.linecorp.armeria.server.logging.LoggingService;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.util.concurrent.Future;

public class TomcatServiceTest extends WebAppContainerTest {

    private static final String SERVICE_NAME = "TomcatServiceTest";

    private final List<Service> tomcatServices = new ArrayList<>();

    @Override
    protected void configureServer(ServerBuilder sb) throws Exception {
        super.configureServer(sb);
        sb.serviceUnder(
                "/jsp/",
                TomcatServiceBuilder.forCurrentClassPath("tomcat_service")
                                    .serviceName(SERVICE_NAME)
                                    .configurator(s -> Collections.addAll(tomcatServices, s.findServices()))
                                    .build()
                                    .decorate(LoggingService::new));

        sb.serviceUnder(
                "/jar/",
                TomcatServiceBuilder.forClassPath(Future.class)
                                    .serviceName("TomcatServiceTest-JAR")
                                    .build()
                                    .decorate(LoggingService::new));

        sb.serviceUnder(
                "/jar_altroot/",
                TomcatServiceBuilder.forClassPath(Future.class, "/io/netty/util/concurrent")
                                    .serviceName("TomcatServiceTest-JAR-AltRoot")
                                    .build()
                                    .decorate(LoggingService::new));
    }

    @Test
    public void testConfigurator() throws Exception {
        assertThat(tomcatServices, hasSize(1));
        assertThat(tomcatServices.get(0).getName(), is(SERVICE_NAME));
    }

    @Test
    public void testJarBasedWebApp() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(
                    new HttpGet(uri("/jar/io/netty/util/concurrent/Future.class")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(res.getFirstHeader(HttpHeaderNames.CONTENT_TYPE.toString()).getValue(),
                           startsWith("application/java"));
            }
        }
    }

    @Test
    public void testJarBasedWebAppWithAlternativeRoot() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(uri("/jar_altroot/Future.class")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(res.getFirstHeader(HttpHeaderNames.CONTENT_TYPE.toString()).getValue(),
                           startsWith("application/java"));
            }
        }
    }
}
