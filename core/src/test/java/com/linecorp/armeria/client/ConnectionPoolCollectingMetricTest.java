/*
 * Copyright 2023 LINE Corporation
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

import java.util.function.Predicate;

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
import io.micrometer.core.instrument.Tag;

public class ConnectionPoolCollectingMetricTest {

    public static final String CONNECTION_OPEN = "armeria.client.connections#count{state=open}";
    public static final String CONNECTION_CLOSED = "armeria.client.connections#count{state=closed}";
    private ConnectionPoolListener connectionPoolListener;
    final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();

    @RegisterExtension
    static ServerExtension server = createServerExtension();

    @RegisterExtension
    static ServerExtension server2 = createServerExtension();

    private static ServerExtension createServerExtension() {
        return new ServerExtension() {
            @Override
            protected void configure(ServerBuilder sb) {
                sb.http(0);
                sb.https(0);
                sb.tlsSelfSigned();
                sb.idleTimeoutMillis(0);
                sb.requestTimeoutMillis(0);
                sb.service("/", (ctx, req) -> HttpResponse.of(OK));
            }
        };
    }

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

            //only check state tag due to addresses and protocol are not fixed and depending on multiple factors
            Predicate<Tag> stateTagOnly = tag -> ConnectionPoolMetrics.STATE.equals(tag.getKey());

            await().untilAsserted(() -> assertThat(MoreMeters.measureAll(registry, stateTagOnly))
                    .containsEntry(CONNECTION_OPEN, 1.0)
                    .doesNotContainKey(CONNECTION_CLOSED));
            final WebClient client2 = WebClient.builder(server2.uri(protocol))
                                               .factory(factory)
                                               .responseTimeoutMillis(0)
                                               .build();

            assertThat(client2.get("/").aggregate().join().status()).isEqualTo(OK);

            await().untilAsserted(() -> assertThat(MoreMeters.measureAll(registry, stateTagOnly))
                    .containsEntry(CONNECTION_OPEN, 2.0)
                    .doesNotContainKey(CONNECTION_CLOSED));

            await().untilAsserted(() -> assertThat(MoreMeters.measureAll(registry, stateTagOnly))
                    .containsEntry(CONNECTION_OPEN, 2.0)
                    .containsEntry(CONNECTION_CLOSED, 2.0));
        }
    }
}
