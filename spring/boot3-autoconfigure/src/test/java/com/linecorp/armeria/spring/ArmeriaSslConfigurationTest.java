/*
 * Copyright 2019 LINE Corporation
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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.spring.ArmeriaSslConfigurationTest.TestConfiguration;

/**
 * This uses {@link ArmeriaAutoConfiguration} for integration tests.
 * {@code application-sslTest.yml} will be loaded with minimal settings to make it work.
 */
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "sslTest" })
@DirtiesContext
@Timeout(10)
class ArmeriaSslConfigurationTest {

    @SpringBootApplication
    static class TestConfiguration {}

    private static final ClientFactory clientFactory =
            ClientFactory.builder()
                         .tlsNoVerify()
                         .addressResolverGroupFactory(eventLoopGroup -> MockAddressResolverGroup.localhost())
                         .build();

    @Inject
    @Nullable
    private Server server;

    private String newUrl(SessionProtocol protocol) {
        assertThat(server).isNotNull();
        return protocol.uriText() + "://127.0.0.1:" + server.activeLocalPort(protocol);
    }

    private void verify(SessionProtocol protocol) {
        final AggregatedHttpResponse res = WebClient.builder(newUrl(protocol))
                                                    .factory(clientFactory)
                                                    .build()
                                                    .get("/ok").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("ok");
    }

    @AfterAll
    static void closeClientFactory() {
        clientFactory.closeAsync();
    }

    @Test
    void https() {
        verify(SessionProtocol.HTTPS);
    }

    @Test
    void http() {
        verify(SessionProtocol.HTTP);
    }
}
