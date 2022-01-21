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

package com.linecorp.armeria.spring.tomcat.demo;

import org.apache.catalina.startup.Tomcat;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.annotation.Bean;

import com.linecorp.armeria.server.tomcat.TomcatService;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;

@SpringBootApplication
public class SpringTomcatApplication {

    /**
     * Bean to configure Armeria Tomcat service.
     * @return configuration bean.
     */
    @Bean
    public ArmeriaServerConfigurator armeriaTomcat(WebServerApplicationContext applicationContext) {
        final WebServer webServer = applicationContext.getWebServer();
        if (webServer instanceof TomcatWebServer) {
            final Tomcat tomcat = ((TomcatWebServer) webServer).getTomcat();

            return serverBuilder -> serverBuilder.service("prefix:/tomcat/api/rest/v1",
                                                          TomcatService.of(tomcat));
        }
        return serverBuilder -> { };
    }

    /**
     * Main method.
     * @param args program args.
     */
    public static void main(String[] args) {
        SpringApplication.run(SpringTomcatApplication.class, args);
    }
}
