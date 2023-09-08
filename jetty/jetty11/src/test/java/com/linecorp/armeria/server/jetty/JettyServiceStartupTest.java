/*
 * Copyright 2020 LINE Corporation
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

import org.eclipse.jetty.annotations.ServletContainerInitializersStarter;
import org.eclipse.jetty.apache.jsp.JettyJasperInitializer;
import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.internal.testing.webapp.WebAppContainerTest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class JettyServiceStartupTest {

    private static final List<Object> jettyBeans = new ArrayList<>();

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
                                .customizer(s -> jettyBeans.addAll(s.getBeans()))
                                .build()
                                .decorate(LoggingService.newDecorator()));

            sb.serviceUnder(
                    "/default/",
                    JettyService.builder()
                                .handler(new DefaultHandler())
                                .build());

            final ResourceHandler resourceHandler = new ResourceHandler();
            resourceHandler.setResourceBase(WebAppContainerTest.webAppRoot().getPath());
            sb.serviceUnder(
                    "/resources/",
                    JettyService.builder()
                                .handler(resourceHandler)
                                .build());
        }
    };

    static WebAppContext newWebAppContext() throws MalformedURLException {
        final File webAppRoot = WebAppContainerTest.webAppRoot();
        final WebAppContext handler = new WebAppContext();
        handler.setContextPath("/");
        handler.setBaseResource(Resource.newResource(webAppRoot));
        handler.setClassLoader(new URLClassLoader(
                new URL[] {
                        Resource.newResource(new File(webAppRoot,
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

    @Test
    void startStop() throws Exception {
        assertThat(server.start()).isNotNull();
        server.stop().join();
    }

    /**
     * Test NPE (Issue #2688).
     * CAUTION: This test is specific to configured logger (Logback). Change it when the underlying logger
     * changes.
     */
    @Test
    void startStopNPE() throws Exception {
        // following two classes:
        //   org.eclipse.jetty.util.component.ContainerLifeCycle & org.eclipse.jetty.server.AbstractConnector
        // cause ArmeriaConnector to return values of getHost() and getPort() before
        // its construction completes when DEBUG logging enabled
        final ch.qos.logback.classic.Logger logger1 =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                        org.eclipse.jetty.util.component.ContainerLifeCycle.class.getName());
        final ch.qos.logback.classic.Logger logger2 =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                        org.eclipse.jetty.server.AbstractConnector.class.getName());
        final ch.qos.logback.classic.Level logger1Level = logger1.getLevel();
        final ch.qos.logback.classic.Level logger2Level = logger2.getLevel();
        try {
            logger1.setLevel(ch.qos.logback.classic.Level.DEBUG);
            logger2.setLevel(ch.qos.logback.classic.Level.DEBUG);

            startStop();
        } finally {
            logger1.setLevel(logger1Level);
            logger2.setLevel(logger2Level);
        }
    }
}
