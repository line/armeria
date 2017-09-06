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
import org.junit.AfterClass;
import org.junit.ClassRule;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.internal.webapp.WebAppContainerTest;
import com.linecorp.armeria.testing.server.ServerRule;

public class UnmanagedJettyServiceTest extends WebAppContainerTest {

    private static Server jetty;

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.port(0, SessionProtocol.HTTP);
            sb.port(0, SessionProtocol.HTTPS);
            sb.sslContext(SessionProtocol.HTTPS,
                          certificate.certificateFile(),
                          certificate.privateKeyFile());

            jetty = new Server(0);
            jetty.setHandler(JettyServiceTest.newWebAppContext());
            jetty.start();
            sb.serviceUnder(
                    "/jsp/",
                    JettyService.forServer(jetty).decorate(LoggingService.newDecorator()));
        }
    };

    @Override
    protected ServerRule server() {
        return server;
    }

    @AfterClass
    public static void stopJetty() throws Exception {
        if (jetty != null) {
            jetty.stop();
            jetty.destroy();
        }
    }
}
