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
package com.linecorp.armeria.server.tomcat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.internal.testing.webapp.WebAppContainerTest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class TomcatServiceDestroyedConnectorTest {

    private static Tomcat tomcatWithWebApp;

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            tomcatWithWebApp = new Tomcat();
            tomcatWithWebApp.setBaseDir("build" + File.separatorChar +
                                        "tomcat-" + TomcatServiceDestroyedConnectorTest.class.getSimpleName());
            tomcatWithWebApp.addWebapp("", WebAppContainerTest.webAppRoot().getAbsolutePath());
            tomcatWithWebApp.start();
            sb.serviceUnder("/api/", TomcatService.of(tomcatWithWebApp.getConnector()));
        }
    };

    @Test
    void serviceUnavailableAfterConnectorIsDestroyed() throws LifecycleException {
        final WebClient client = WebClient.of(server.httpUri());
        assertThat(client.get("/api/").aggregate().join().status()).isSameAs(HttpStatus.OK);
        tomcatWithWebApp.stop();
        tomcatWithWebApp.getConnector().destroy();
        assertThat(client.get("/api/").aggregate().join().status()).isSameAs(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
