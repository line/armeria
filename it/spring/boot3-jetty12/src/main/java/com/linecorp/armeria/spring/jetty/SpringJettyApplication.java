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

package com.linecorp.armeria.spring.jetty;

import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Loader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.embedded.jetty.JettyServerCustomizer;
import org.springframework.boot.web.embedded.jetty.JettyServletWebServerFactory;
import org.springframework.boot.web.embedded.jetty.JettyWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linecorp.armeria.server.jetty.JettyService;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;

import jakarta.servlet.Servlet;

@SpringBootApplication
public class SpringJettyApplication {

    /**
     * Bean to configure Armeria Jetty service.
     */
    @Bean
    public ArmeriaServerConfigurator armeriaTomcat(WebServerApplicationContext applicationContext) {
        final WebServer webServer = applicationContext.getWebServer();
        if (webServer instanceof JettyWebServer) {
            final Server jettyServer = ((JettyWebServer) webServer).getServer();

            return serverBuilder -> serverBuilder.service("prefix:/jetty/api/rest/v1",
                                                          JettyService.of(jettyServer));
        }
        return serverBuilder -> {};
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({ Servlet.class, Server.class, Loader.class, WebAppContext.class })
    static class EmbeddedJetty {

        @Bean
        JettyServletWebServerFactory jettyServletWebServerFactory(
                ObjectProvider<JettyServerCustomizer> serverCustomizers) {
            final JettyServletWebServerFactory factory = new ArmeriaJettyServletWebServerFactory();
            factory.getServerCustomizers().addAll(serverCustomizers.orderedStream().toList());
            return factory;
        }
    }

    static final class ArmeriaJettyServletWebServerFactory extends JettyServletWebServerFactory {

        @Override
        protected JettyWebServer getJettyWebServer(Server server) {
            return new JettyWebServer(server, true);
        }
    }

    /**
     * Main method.
     * @param args program args.
     */
    public static void main(String[] args) {
        SpringApplication.run(SpringJettyApplication.class, args);
    }
}
