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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.internal.testing.webapp.WebAppContainerTest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

import io.netty.util.NetUtil;

class UnmanagedTomcatServiceTest {

    private static Tomcat tomcatWithWebApp;
    private static Tomcat tomcatWithoutWebApp;

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            // Prepare Tomcat instances.
            tomcatWithWebApp = new Tomcat();
            tomcatWithWebApp.setPort(0);
            tomcatWithWebApp.setBaseDir("build" + File.separatorChar +
                                        "tomcat-" + UnmanagedTomcatServiceTest.class.getSimpleName() + "-1");

            tomcatWithWebApp.addWebapp("", WebAppContainerTest.webAppRoot().getAbsolutePath());
            TomcatUtil.engine(tomcatWithWebApp.getService(), "foo").setName("tomcatWithWebApp");

            tomcatWithoutWebApp = new Tomcat();
            tomcatWithoutWebApp.setPort(0);
            tomcatWithoutWebApp.setBaseDir("build" + File.separatorChar +
                                           "tomcat-" + UnmanagedTomcatServiceTest.class.getSimpleName() + "-2");
            assertThat(TomcatUtil.engine(tomcatWithoutWebApp.getService(), "bar")).isNotNull();

            // Start the Tomcats.
            tomcatWithWebApp.start();
            tomcatWithoutWebApp.start();

            // Bind them to the Server.
            sb.serviceUnder("/empty/", TomcatService.of(new Connector(), "someHost"))
              .serviceUnder("/some-webapp-nohostname/",
                            TomcatService.of(tomcatWithWebApp.getConnector()))
              .serviceUnder("/no-webapp/", TomcatService.of(tomcatWithoutWebApp))
              .serviceUnder("/some-webapp/", TomcatService.of(tomcatWithWebApp));
        }
    };

    @AfterAll
    static void destroyTomcat() throws Exception {
        if (tomcatWithWebApp != null) {
            tomcatWithWebApp.stop();
            tomcatWithWebApp.destroy();
        }
        if (tomcatWithoutWebApp != null) {
            tomcatWithoutWebApp.stop();
            tomcatWithoutWebApp.destroy();
        }
    }

    @Test
    void serviceUnavailable() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.httpUri() + "/empty/"))) {
                // as connector is not configured, TomcatServiceInvocationHandler will throw.
                assertThat(res.getStatusLine().toString()).isEqualTo(
                        "HTTP/1.1 503 Service Unavailable");
            }
        }
    }

    @Test
    void unconfiguredWebApp() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.httpUri() + "/no-webapp/"))) {
                // When no webapp is configured, Tomcat sends:
                // - 400 Bad Request response for 9.0.10+
                // - 404 Not Found for other versions
                final String statusLine = res.getStatusLine().toString();
                assertThat(statusLine).matches("^HTTP/1\\.1 (400 Bad Request|404 Not Found)$");
            }
        }
    }

    @Test
    void ok() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.httpUri() + "/some-webapp/"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
            }
        }
    }

    @Test
    void okNoHostName() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(
                    server.httpUri() + "/some-webapp-nohostname/"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
            }
        }
    }

    @Test
    void okWithoutAuthorityHeader() throws Exception {
        final int port = server.httpPort();
        try (Socket s = new Socket(NetUtil.LOCALHOST, port)) {
            final InputStream in = s.getInputStream();
            final OutputStream out = s.getOutputStream();
            out.write(("GET /some-webapp/ HTTP/1.1\r\n" +
                       "Content-Length: 0\r\n" +
                       "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));

            try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
                assertThat(br.readLine()).isEqualTo("HTTP/1.1 200 OK");
            }
        }
    }
}
