/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.server.athenz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.athenz.TokenType;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.athenz.resource.AthenzResourceProvider;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AthenzServiceReconfigureTest {

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

        final AthenzService athenzService =
                new AthenzService((ctx, req) -> HttpResponse.of(HttpStatus.OK),
                                  new AthenzAuthorizer(null), AthenzResourceProvider.of("files"),
                                  "obtain", ImmutableList.of(TokenType.ACCESS_TOKEN),
                                  new MeterIdPrefix("armeria.server.athenz"), null, "files");

        final Thread reconfigureThread = new Thread(() -> server.server().reconfigure(sb -> {
            sb.service("/blocker", blocker);
            sb.service("/athenz", athenzService);
        }));
        reconfigureThread.start();

        try {
            assertThat(blockerAddedReached.await(10, TimeUnit.SECONDS)).isTrue();

            final BlockingWebClient client = server.blockingWebClient();
            assertThat(client.get("/athenz").status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        } finally {
            releaseBlocker.countDown();
            reconfigureThread.join(TimeUnit.SECONDS.toMillis(10));
        }
    }
}
