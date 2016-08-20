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

package com.linecorp.armeria.server.http.jetty;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;

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
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.Test;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.http.WebAppContainerTest;
import com.linecorp.armeria.server.logging.LoggingService;

import io.netty.handler.codec.http.HttpHeaderNames;

public class JettyServiceTest extends WebAppContainerTest {

    private final List<Object> jettyBeans = new ArrayList<>();

    @Override
    protected void configureServer(ServerBuilder sb) throws Exception {
        super.configureServer(sb);
        sb.serviceUnder(
                "/jsp/",
                new JettyServiceBuilder()
                        .handler(newWebAppContext())
                        .configurator(s -> jettyBeans.addAll(s.getBeans()))
                        .build()
                        .decorate(LoggingService::new));

        sb.serviceUnder(
                "/default/",
                new JettyServiceBuilder().handler(new DefaultHandler()).build());
    }

    static WebAppContext newWebAppContext() throws MalformedURLException {
        final WebAppContext handler = new WebAppContext();
        handler.setContextPath("/");
        handler.setBaseResource(Resource.newClassPathResource("/tomcat_service"));
        handler.setClassLoader(new URLClassLoader(
                new URL[] {
                        Resource.newClassPathResource("/tomcat_service/WEB-INF/lib/hello.jar").getURI().toURL()
                },
                JettyService.class.getClassLoader()));

        handler.addBean(new ServletContainerInitializersStarter(handler), true);
        handler.setAttribute(
                "org.eclipse.jetty.containerInitializers",
                Collections.singletonList(new ContainerInitializer(new JettyJasperInitializer(), null)));
        return handler;
    }

    @Test
    public void testConfigurator() throws Exception {
        assertThat(jettyBeans, hasItems(instanceOf(ThreadPool.class),
                                        instanceOf(WebAppContext.class)));
    }

    @Test
    public void testDefaultHandlerFavicon() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(
                    new HttpGet(uri("/default/favicon.ico")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(res.getFirstHeader(HttpHeaderNames.CONTENT_TYPE.toString()).getValue(),
                           startsWith("image/x-icon"));
                assertThat(EntityUtils.toByteArray(res.getEntity()).length, is(greaterThan(0)));
            }
        }
    }
}
