/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.internal;

import static org.apache.http.HttpVersion.HTTP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.http.HttpClient;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.http.AbstractHttpService;
import com.linecorp.armeria.testing.server.ServerRule;

import io.netty.channel.embedded.EmbeddedChannel;

public class ConnectionLimitingHandlerTest {

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.numWorkers(1);
            sb.port(0, HTTP);
            sb.maxNumConnections(2);

            sb.serviceUnder("/delay", new AbstractHttpService() {
                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                    final long delayMillis = Long.parseLong(ctx.mappedPath().substring(1));
                    ctx.eventLoop().schedule(() -> res.respond(HttpStatus.OK),
                                             delayMillis, TimeUnit.MILLISECONDS);
                }
            });
        }
    };

    @Test
    public void testExceedMaxNumConnectionsOnServer() {
        CompletableFuture<AggregatedHttpMessage> f1 = newHttpClient().get("/delay/2000").aggregate();
        CompletableFuture<AggregatedHttpMessage> f2 = newHttpClient().get("/delay/2000").aggregate();

        threadSleepQuietly(1000, 0);

        CompletableFuture<AggregatedHttpMessage> f3 = newHttpClient().get("/delay/2000").aggregate();

        assertThat(f1.join().status()).isEqualTo(HttpStatus.OK);
        assertThat(f2.join().status()).isEqualTo(HttpStatus.OK);

        assertThatThrownBy(() -> f3.join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ClosedSessionException.class);
    }

    @Test
    public void testExceedMaxNumConnections() {
        ConnectionLimitingHandler handler = new ConnectionLimitingHandler(1);

        EmbeddedChannel ch1 = new EmbeddedChannel(handler);
        ch1.writeInbound(ch1);
        assertThat(handler.numConnections()).isEqualTo(1);
        assertThat(ch1.isActive()).isTrue();

        EmbeddedChannel ch2 = new EmbeddedChannel(handler);
        ch2.writeInbound(ch2);
        assertThat(handler.numConnections()).isEqualTo(1);
        assertThat(ch2.isActive()).isFalse();

        ch1.close();
        assertThat(handler.numConnections()).isEqualTo(0);
    }

    @Test
    public void testMaxNumConnectionsRange() {
        ConnectionLimitingHandler handler = new ConnectionLimitingHandler(Integer.MAX_VALUE);
        assertThat(handler.maxNumConnections()).isEqualTo(Integer.MAX_VALUE);

        assertThatThrownBy(() -> new ConnectionLimitingHandler(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxNumConnections: " + 0 + " (expected: > 0)");

        assertThatThrownBy(() -> new ConnectionLimitingHandler(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxNumConnections: " + -1 + " (expected: > 0)");
    }

    private HttpClient newHttpClient() {
        return Clients.newClient("none+http://127.0.0.1:" + server.httpPort(), HttpClient.class);
    }

    private static void threadSleepQuietly(long millis, int nanos) {
        try {
            Thread.sleep(millis, nanos);
        } catch (Exception e) {
            // ignore
        }
    }
}
