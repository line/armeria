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
package com.linecorp.armeria.spring;

import static org.assertj.core.api.Assertions.assertThat;

import javax.inject.Inject;

import org.junit.AfterClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.spring.LocalArmeriaPortTest.TestConfiguration;

/**
 * Tests for {@link LocalArmeriaPort} when https.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "sslTest" })
@DirtiesContext
public class LocalArmeriaPortHttpsTest {

    @SpringBootApplication
    @Import(ArmeriaOkServiceConfiguration.class)
    static class TestConfiguration {}

    private static final ClientFactory clientFactory =
            ClientFactory.builder()
                         .tlsNoVerify()
                         .addressResolverGroupFactory(it -> MockAddressResolverGroup.localhost())
                         .build();

    @Inject
    private Server server;
    @LocalArmeriaPort(SessionProtocol.HTTPS)
    private Integer port;

    private String newUrl(String scheme) {
        return scheme + "://127.0.0.1:" + port;
    }

    @AfterClass
    public static void closeClientFactory() {
        clientFactory.closeAsync();
    }

    @Test
    public void testPortConfiguration() throws Exception {
        final Integer actualPort = server.activeLocalPort(SessionProtocol.HTTPS);
        assertThat(actualPort).isEqualTo(port);
    }

    @Test
    public void testHttpServiceRegistrationBean() throws Exception {
        final HttpResponse response = WebClient.builder(newUrl("https"))
                                               .factory(clientFactory)
                                               .build()
                                               .get("/ok");
        final AggregatedHttpResponse res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("ok");
    }
}
