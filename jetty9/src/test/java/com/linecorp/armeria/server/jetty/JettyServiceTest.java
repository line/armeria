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

package com.linecorp.armeria.server.jetty;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.annotations.ServletContainerInitializersStarter;
import org.eclipse.jetty.apache.jsp.JettyJasperInitializer;
import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.internal.testing.webapp.WebAppContainerTest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

import io.netty.handler.codec.http.HttpHeaderNames;

class JettyServiceTest extends WebAppContainerTest {

    private static final List<Object> jettyBeans = new ArrayList<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();

            sb.serviceUnder(
                    "/jsp/",
                    JettyService.builder()
                                .handler(newWebAppContext())
                                .configurator(s -> jettyBeans.addAll(s.getBeans()))
                                .build()
                                .decorate(LoggingService.newDecorator()));

            sb.serviceUnder(
                    "/default/",
                    JettyService.builder()
                                .handler(new DefaultHandler())
                                .build());

            final ResourceHandler resourceHandler = new ResourceHandler();
            resourceHandler.setResourceBase(webAppRoot().getPath());
            sb.serviceUnder(
                    "/resources/",
                    JettyService.builder()
                                .handler(resourceHandler)
                                .build());
        }
    };

    static WebAppContext newWebAppContext() throws MalformedURLException {
        final WebAppContext handler = new WebAppContext();
        handler.setContextPath("/");
        handler.setBaseResource(Resource.newResource(webAppRoot()));
        handler.setClassLoader(new URLClassLoader(
                new URL[] {
                        Resource.newResource(new File(webAppRoot(),
                                                      "WEB-INF" + File.separatorChar +
                                                      "lib" + File.separatorChar +
                                                      "hello.jar")).getURI().toURL()
                },
                JettyService.class.getClassLoader()));

        handler.addBean(new ServletContainerInitializersStarter(handler), true);
        handler.setAttribute(
                "org.eclipse.jetty.containerInitializers",
                Collections.singletonList(new ContainerInitializer(new JettyJasperInitializer(), null)));
        return handler;
    }

    @Override
    protected ServerExtension server() {
        return server;
    }

    @Test
    void configurator() throws Exception {
        assertThat(jettyBeans)
                  .hasAtLeastOneElementOfType(ThreadPool.class)
                  .hasAtLeastOneElementOfType(WebAppContext.class);
    }

    @Test
    void defaultHandlerFavicon() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(
                    new HttpGet(server.httpUri() + "/default/favicon.ico"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(res.getFirstHeader(HttpHeaderNames.CONTENT_TYPE.toString()).getValue())
                              .startsWith("image/x-icon");
                assertThat(EntityUtils.toByteArray(res.getEntity()).length)
                          .isGreaterThan(0);
            }
        }
    }

    @Test
    void resourceHandlerWithLargeResource() throws Exception {
        testLarge("/resources/large.txt");
    }
}
