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

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;

import org.eclipse.jetty.annotations.ServletContainerInitializersStarter;
import org.eclipse.jetty.apache.jsp.JettyJasperInitializer;
import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.internal.testing.webapp.WebAppContainerMutualTlsTest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

class JettyServiceMutualTlsTest extends WebAppContainerMutualTlsTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.tlsCustomizer(sslCtxBuilder -> {
                sslCtxBuilder.clientAuth(ClientAuth.REQUIRE);
                sslCtxBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            });

            sb.serviceUnder(
                    "/jsp/",
                    JettyService.builder()
                                .handler(newWebAppContext())
                                .build()
                                .decorate(LoggingService.newDecorator()));
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
}
