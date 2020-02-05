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

import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.internal.testing.webapp.WebAppContainerTest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class UnmanagedJettyServiceTest extends WebAppContainerTest {

    private static Server jetty;

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();

            jetty = new Server(0);
            jetty.setHandler(JettyServiceTest.newWebAppContext());
            jetty.start();
            sb.serviceUnder(
                    "/jsp/",
                    JettyService.of(jetty).decorate(LoggingService.newDecorator()));
        }
    };

    @Override
    protected ServerExtension server() {
        return server;
    }

    @AfterAll
    static void stopJetty() throws Exception {
        if (jetty != null) {
            jetty.stop();
            jetty.destroy();
        }
    }
}
