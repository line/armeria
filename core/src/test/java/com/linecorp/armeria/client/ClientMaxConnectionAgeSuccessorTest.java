/*
 * Copyright 2026 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ClientMaxConnectionAgeSuccessorTest {

    private static final long MAX_CONNECTION_AGE_MILLIS = 1000;

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.idleTimeoutMillis(0);
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    @EnumSource(value = SessionProtocol.class, names = { "H2C", "H2" })
    @ParameterizedTest
    void steadyTrafficNeverWaitsForAReplacementConnection(SessionProtocol protocol) throws Exception {
        final CountingConnectionPoolListener connections = new CountingConnectionPoolListener();
        try (ClientFactory factory = ClientFactory.builder()
                                                  .connectionPoolListener(connections)
                                                  .idleTimeoutMillis(0)
                                                  .maxConnectionAgeMillis(MAX_CONNECTION_AGE_MILLIS)
                                                  .tlsNoVerify()
                                                  .build()) {
            final WebClient client = WebClient.builder(server.uri(protocol))
                                              .factory(factory)
                                              .build();

            final List<Integer> requestsThatWaitedForAConnection = new ArrayList<>();
            final long deadlineNanos =
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(3 * MAX_CONNECTION_AGE_MILLIS + 500);
            for (int i = 0; System.nanoTime() < deadlineNanos; i++) {
                final ClientRequestContext ctx;
                try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                    assertThat(client.get("/").aggregate().join().status()).isEqualTo(HttpStatus.OK);
                    ctx = captor.get();
                }
                if (ctx.log().whenComplete().join().connectionTimings() != null) {
                    requestsThatWaitedForAConnection.add(i);
                }
                Thread.sleep(20);
            }

            assertThat(connections.opened())
                    .as("connections must still rotate at the max age")
                    .isGreaterThanOrEqualTo(3);
            assertThat(requestsThatWaitedForAConnection)
                    .as("only the very first request may wait for a connection to be established")
                    .containsExactly(0);
        }
    }
}
