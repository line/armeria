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

import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.internal.testing.webapp.WebAppContainerMutualTlsTest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

class TomcatServiceMutualTlsTest extends WebAppContainerMutualTlsTest {

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
                    TomcatService.builder(webAppRoot())
                                 .serviceName("TomcatServiceMutualTlsTest")
                                 .build()
                                 .decorate(LoggingService.newDecorator()));
        }
    };

    @Override
    protected ServerExtension server() {
        return server;
    }
}
