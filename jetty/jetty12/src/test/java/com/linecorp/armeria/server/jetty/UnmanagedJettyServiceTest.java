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

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.internal.testing.webapp.WebAppContainerTest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

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

    @Test
    @Override
    public void addressesAndPorts_127001() throws Exception {
        final AggregatedHttpResponse response = WebClient.of(server().httpUri()).blocking()
                                                         .get("/jsp/addrs_and_ports.jsp");
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.contentType().toString()).startsWith("text/html");
        final String actualContent = CR_OR_LF.matcher(response.contentUtf8())
                                             .replaceAll("");
        assertThat(actualContent).matches(
                "<html><body>" +
                "<p>RemoteAddr: 127\\.0\\.0\\.1</p>" +
                "<p>RemoteHost: 127\\.0\\.0\\.1</p>" +
                "<p>RemotePort: [1-9][0-9]+</p>" +
                "<p>LocalAddr: (?!null)[^<]+</p>" +
                // In Jetty 12, ServletRequest.getLocalName() returns the IP address if it is resolved.
                "<p>LocalName: 127\\.0\\.0\\.1</p>" +
                "<p>LocalPort: " + server().httpPort() + "</p>" +
                "<p>ServerName: 127\\.0\\.0\\.1</p>" +
                "<p>ServerPort: " + server().httpPort() + "</p>" +
                "</body></html>");
    }

    @Test
    @Override
    public void addressesAndPorts_localhost() throws Exception {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/jsp/addrs_and_ports.jsp", "Host",
                                                         "localhost:1111");
        final AggregatedHttpResponse response = WebClient.of(server().httpUri()).blocking().execute(headers);
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.contentType().toString()).startsWith("text/html");
        final String actualContent = CR_OR_LF.matcher(response.contentUtf8())
                                             .replaceAll("");
        assertThat(actualContent).matches(
                "<html><body>" +
                "<p>RemoteAddr: 127\\.0\\.0\\.1</p>" +
                "<p>RemoteHost: 127\\.0\\.0\\.1</p>" +
                "<p>RemotePort: [1-9][0-9]+</p>" +
                "<p>LocalAddr: (?!null)[^<]+</p>" +
                // In Jetty 12, ServletRequest.getLocalName() returns the IP address if it is resolved.
                "<p>LocalName: 127\\.0\\.0\\.1</p>" +
                "<p>LocalPort: " + server().httpPort() + "</p>" +
                "<p>ServerName: localhost</p>" +
                "<p>ServerPort: 1111</p>" +
                "</body></html>");
    }
}
