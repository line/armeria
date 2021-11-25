/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.internal.testing.webapp;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.hamcrest.Matchers.greaterThan;

import java.io.File;
import java.math.BigDecimal;

import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.io.BaseEncoding;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

/**
 * Tests a web application container {@link Service}.
 */
public abstract class WebAppContainerMutualTlsTest {

    @RegisterExtension
    static final SelfSignedCertificateExtension ssc = new SelfSignedCertificateExtension("mutual.tls.test");

    /**
     * Returns the doc-base directory of the test web application.
     */
    public static File webAppRoot() {
        return WebAppContainerTest.webAppRoot();
    }

    /**
     * Returns the {@link ServerExtension} that provides the {@link Server} that serves the {@link Service}s
     * this test runs against.
     */
    protected abstract ServerExtension server();

    @ParameterizedTest
    @CsvSource({ "H1", "H2" })
    public void mutualTlsAttrs(SessionProtocol sessionProtocol) throws Exception {
        try (ClientFactory clientFactory = ClientFactory
                .builder()
                .tlsCustomizer(sslCtxBuilder -> {
                    sslCtxBuilder.keyManager(ssc.privateKey(), ssc.certificate());
                    sslCtxBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
                })
                .build()) {

            final WebClient client = WebClient.builder(server().uri(sessionProtocol))
                                              .factory(clientFactory)
                                              .build();

            final AggregatedHttpResponse res = client.get("/jsp/mutual_tls.jsp").aggregate().join();
            final SSLSession sslSession = server().requestContextCaptor().take().sslSession();
            final String expectedId;
            if (sslSession.getId() != null) {
                expectedId = BaseEncoding.base16().encode(sslSession.getId());
            } else {
                expectedId = "";
            }

            assertThatJson(res.contentUtf8())
                    .node("sessionId").isStringEqualTo(expectedId)
                    .node("cipherSuite").isStringEqualTo(sslSession.getCipherSuite())
                    .node("keySize").matches(greaterThan(BigDecimal.ZERO))
                    .node("peerCerts").isArray().ofLength(1).thatContains("CN=mutual.tls.test");
        }
    }
}
