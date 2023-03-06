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
package com.linecorp.armeria.client;

import static com.linecorp.armeria.common.HttpStatus.OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.micrometer.core.instrument.MeterRegistry;

public class ConnectionPoolCollectingMetricTest {

    private ConnectionPoolListener connectionPoolListener;
    final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.idleTimeoutMillis(0);
            sb.requestTimeoutMillis(0);
            sb.service("/", (ctx, req) -> HttpResponse.of(OK));
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @RegisterExtension
    static ServerExtension server2 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.idleTimeoutMillis(0);
            sb.requestTimeoutMillis(0);
            sb.service("/", (ctx, req) -> HttpResponse.of(OK));
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @BeforeEach
    void setUp() {
        connectionPoolListener = ConnectionPoolListener.metricCollecting(registry);
    }

    @EnumSource(value = SessionProtocol.class, names = "PROXY", mode = EnumSource.Mode.EXCLUDE)
    @ParameterizedTest
    void test(SessionProtocol protocol) throws Exception {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .connectionPoolListener(connectionPoolListener)
                                                  .idleTimeoutMillis(0)
                                                  .maxConnectionAgeMillis(1000)
                                                  .tlsNoVerify()
                                                  .build()) {
            final WebClient client = WebClient.builder(server.uri(protocol))
                                              .factory(factory)
                                              .responseTimeoutMillis(0)
                                              .build();

            assertThat(client.get("/").aggregate().join().status()).isEqualTo(OK);

            assertThat(MoreMeters.measureAll(registry))
                    .containsEntry("armeria.client.connections#count{state=open}", 1.0)
                    .containsEntry("armeria.client.connections#count{state=close}", 0.0);

            final WebClient client2 = WebClient.builder(server2.uri(protocol))
                                               .factory(factory)
                                               .responseTimeoutMillis(0)
                                               .build();

            assertThat(client2.get("/").aggregate().join().status()).isEqualTo(OK);

            assertThat(MoreMeters.measureAll(registry))
                    .containsEntry("armeria.client.connections#count{state=open}", 2.0)
                    .containsEntry("armeria.client.connections#count{state=close}", 0.0);

            await().untilAsserted(() -> {
                assertThat(MoreMeters.measureAll(registry))
                        .containsEntry("armeria.client.connections#count{state=open}", 2.0)
                        .containsEntry("armeria.client.connections#count{state=close}", 2.0);
            });
        }
    }
}
