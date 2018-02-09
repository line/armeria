/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.server.ServerRule;

public class ServerListenerTest {
    private static long STARTING_AT = 0L;
    private static long STARTED_AT = 0L;
    private static long STOPPING_AT = 0L;
    private static long STOPPED_AT = 0L;

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.meterRegistry(PrometheusMeterRegistries.newRegistry());

            final Service<HttpRequest, HttpResponse> immediateResponseOnIoThread =
                    new EchoService().decorate(LoggingService.newDecorator());

            sb.service("/", immediateResponseOnIoThread);

            // Record when the method triggered
            ServerListener sl = new ServerListenerBuilder()
                    .onStarting(() -> {
                        ServerListenerTest.STARTING_AT = System.currentTimeMillis();
                    })
                    .onStarted(() -> {
                        ServerListenerTest.STARTED_AT = System.currentTimeMillis();
                    })
                    .onStopping(() -> {
                        ServerListenerTest.STOPPING_AT = System.currentTimeMillis();
                    }, () -> {
                        ServerListenerTest.STARTING_AT = 0L;
                    })
                    .onStopped(() -> {
                        ServerListenerTest.STOPPED_AT = System.currentTimeMillis();
                    }, () -> {
                        ServerListenerTest.STARTED_AT = 0L;
                    })
                    .build();
            sb.serverListener(sl);
        }
    };

    @Before
    public void startServer() {
        server.start();
    }

    @Test
    public void testServerListener() throws Exception {
        // Before stop
        assertThat(ServerListenerTest.STARTING_AT).isGreaterThan(0L);
        assertThat(ServerListenerTest.STARTED_AT).isGreaterThan(ServerListenerTest.STARTING_AT);
        assertThat(ServerListenerTest.STOPPING_AT).isEqualTo(0L);
        assertThat(ServerListenerTest.STOPPED_AT).isEqualTo(0L);

        final Server server = ServerListenerTest.server.server();
        server.stop().get();

        // After stop
        assertThat(ServerListenerTest.STARTING_AT).isEqualTo(0L);
        assertThat(ServerListenerTest.STARTED_AT).isEqualTo(0L);
        assertThat(ServerListenerTest.STOPPING_AT).isGreaterThan(0L);
        assertThat(ServerListenerTest.STOPPED_AT).isGreaterThan(ServerListenerTest.STOPPING_AT);
    }

    private static class EchoService extends AbstractHttpService {
        @Override
        protected final HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
            return HttpResponse.from(req.aggregate()
                                        .thenApply(this::echo)
                                        .exceptionally(CompletionActions::log));
        }

        protected HttpResponse echo(AggregatedHttpMessage aReq) {
            return HttpResponse.of(
                    HttpHeaders.of(HttpStatus.OK),
                    aReq.content());
        }
    }
}
