/*
 * Copyright 2022 LINE Corporation
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

import java.io.IOError;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.internal.testing.webapp.WebAppContainerMutualTlsTest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.NetUtil;

class JettyServiceTlsReverseDnsLookupTest extends WebAppContainerMutualTlsTest {

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
                                .handler(JettyServiceTest.newWebAppContext())
                                .tlsReverseDnsLookup(true)
                                .build()
                                .decorate(LoggingService.newDecorator()));
        }
    };

    @Override
    protected ServerExtension server() {
        return server;
    }

    @Override
    protected String expectedRemoteHost() {
        try {
            // `ServletRequest.getRemoteHost()` must return the reverse-lookup result of `127.0.0.1`
            // because we enabled `tlsReverseDnsLookup`.
            final String localhostName = InetAddress.getByName("127.0.0.1").getHostName();

            // Make sure reverse-lookup result isn't an IP address.
            assertThat(!NetUtil.isValidIpV4Address(localhostName) &&
                       !NetUtil.isValidIpV6Address(localhostName))
                    .as("Reverse DNS lookup must not return an IP address")
                    .isTrue();

            return localhostName;
        } catch (UnknownHostException e) {
            // Failed to perform a reverse-lookup.
            throw new IOError(e);
        }
    }
}
