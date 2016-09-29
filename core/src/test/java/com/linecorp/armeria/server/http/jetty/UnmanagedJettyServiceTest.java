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

import org.eclipse.jetty.server.Server;
import org.junit.AfterClass;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.http.WebAppContainerTest;
import com.linecorp.armeria.server.logging.LoggingService;

public class UnmanagedJettyServiceTest extends WebAppContainerTest {

    private static Server jetty;

    @Override
    protected void configureServer(ServerBuilder sb) throws Exception {
        super.configureServer(sb);
        jetty = new Server(0);
        jetty.setHandler(JettyServiceTest.newWebAppContext());
        jetty.start();
        sb.serviceUnder(
                "/jsp/",
                JettyService.forServer(jetty).decorate(LoggingService::new));
    }

    @AfterClass
    public static void stopJetty() throws Exception {
        jetty.stop();
        jetty.destroy();
    }
}
