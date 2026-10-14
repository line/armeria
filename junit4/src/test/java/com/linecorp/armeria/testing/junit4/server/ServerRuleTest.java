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

package com.linecorp.armeria.testing.junit4.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPortBindException;

public class ServerRuleTest {

    private static final Statement NOOP = new Statement() {
        @Override
        public void evaluate() {}
    };

    @Test
    public void startupFailureIsNotRetriedByDefault() throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        try (ServerSocket occupiedPort = new ServerSocket(0)) {
            final ServerRule rule = new ServerRule() {
                @Override
                protected void configure(ServerBuilder sb) {
                    attempts.incrementAndGet();
                    sb.http(occupiedPort.getLocalPort())
                      .service("/hello", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
                }
            };
            assertThatThrownBy(() -> rule.apply(NOOP, Description.EMPTY).evaluate())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(ServerPortBindException.class);
            assertThat(attempts.get()).isOne();
        }
    }

    @Test
    public void retriesStartupFailuresUntilSuccessful() throws Throwable {
        for (int successfulAttempt : new int[] { 1, 2, 5 }) {
            final AtomicInteger attempts = new AtomicInteger();
            final AtomicReference<Server> startedServer = new AtomicReference<>();
            try (ServerSocket occupiedPort = new ServerSocket(0)) {
                final ServerRule rule = new ServerRule() {
                    @Override
                    protected int numAttemptsOnStartupFailure() {
                        return 5;
                    }

                    @Override
                    protected void configure(ServerBuilder sb) {
                        final int port = attempts.incrementAndGet() < successfulAttempt ?
                                         occupiedPort.getLocalPort() : 0;
                        sb.http(port).service("/hello", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
                    }
                };
                final Statement statement = new Statement() {
                    @Override
                    public void evaluate() {
                        startedServer.set(rule.server());
                        assertThat(attempts.get()).isEqualTo(successfulAttempt);
                        assertThat(rule.start()).isSameAs(rule.server());
                        assertThat(attempts.get()).isEqualTo(successfulAttempt);
                        assertThat(rule.webClient().get("/hello").aggregate().join().status())
                                .isEqualTo(HttpStatus.OK);
                    }
                };
                try {
                    rule.apply(statement, Description.EMPTY).evaluate();
                    assertThatThrownBy(rule::server).isInstanceOf(IllegalStateException.class);
                } finally {
                    final Server server = startedServer.get();
                    if (server != null) {
                        server.stop().join();
                    } else {
                        rule.stop().join();
                    }
                }
            }
        }
    }

    @Test
    public void startupAttemptsAreBounded() throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        try (ServerSocket occupiedPort = new ServerSocket(0)) {
            final ServerRule rule = new ServerRule() {
                @Override
                protected int numAttemptsOnStartupFailure() {
                    return 5;
                }

                @Override
                protected void configure(ServerBuilder sb) {
                    attempts.incrementAndGet();
                    sb.http(occupiedPort.getLocalPort())
                      .service("/hello", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
                }
            };
            assertThatThrownBy(() -> rule.apply(NOOP, Description.EMPTY).evaluate())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(ServerPortBindException.class);
            assertThat(attempts.get()).isEqualTo(5);
            assertThatThrownBy(rule::server).isInstanceOf(IllegalStateException.class);
        }
    }
}
