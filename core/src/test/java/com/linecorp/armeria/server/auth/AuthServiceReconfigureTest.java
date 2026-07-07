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

package com.linecorp.armeria.server.auth;

import static com.linecorp.armeria.common.util.UnmodifiableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AuthServiceReconfigureTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    @Test
    void shouldNotFailWhenServedDuringReconfigure() throws Exception {
        final CountDownLatch blockerAddedReached = new CountDownLatch(1);
        final CountDownLatch releaseBlocker = new CountDownLatch(1);

        final HttpService blocker = new HttpService() {
            @Override
            public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                return HttpResponse.of(HttpStatus.OK);
            }

            @Override
            public void serviceAdded(ServiceConfig cfg) throws Exception {
                blockerAddedReached.countDown();
                releaseBlocker.await();
            }
        };

        final HttpService authService =
                ((HttpService) (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                        .decorate(AuthService.newDecorator((ctx, req) -> completedFuture(true)));

        final Thread reconfigureThread = new Thread(() -> server.server().reconfigure(sb -> {
            // Registration order matters: "/blocker" blocks reconfigure before "/auth".serviceAdded().
            sb.service("/blocker", blocker);
            sb.service("/auth", authService);
        }));
        reconfigureThread.start();

        try {
            assertThat(blockerAddedReached.await(10, TimeUnit.SECONDS)).isTrue();

            final BlockingWebClient client = server.blockingWebClient();
            assertThat(client.get("/auth").status()).isEqualTo(HttpStatus.OK);
        } finally {
            releaseBlocker.countDown();
            reconfigureThread.join(TimeUnit.SECONDS.toMillis(10));
        }
    }
}
