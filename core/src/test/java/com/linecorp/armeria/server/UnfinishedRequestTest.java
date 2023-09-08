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

package com.linecorp.armeria.server;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class UnfinishedRequestTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.idleTimeoutMillis(0);
            sb.requestTimeoutMillis(0);

            final AtomicInteger requestCounter = new AtomicInteger();
            sb.service("/", (ctx, req) -> {
                if (requestCounter.incrementAndGet() == 2) {
                    ctx.initiateConnectionShutdown(0);
                }
                return HttpResponse.delayed(HttpResponse.of(200), Duration.ofMinutes(1));
            });
        }
    };

    @Test
    void shouldCompleteUnfinishedRequestWhenConnectionIsClosed() throws Exception {
        final WebClient client = server.webClient(cb -> cb.responseTimeoutMillis(0));
        client.get("/").aggregate();
        client.get("/").aggregate();

        final ServiceRequestContext ctx1 = server.requestContextCaptor().take();
        final ServiceRequestContext ctx2 = server.requestContextCaptor().take();
        // Make sure that `HttpServerHandler.cleanup()` aborts all unfinished requests successfully.
        ctx1.log().whenComplete().join();
        ctx2.log().whenComplete().join();
    }
}
