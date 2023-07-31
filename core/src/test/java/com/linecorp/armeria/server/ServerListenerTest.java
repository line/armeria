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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.assertj.core.util.Lists;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ServerListenerTest {
    private static long STARTING_AT;
    private static long STARTED_AT;
    private static long STOPPING_AT;
    private static long STOPPED_AT;

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.meterRegistry(PrometheusMeterRegistries.newRegistry())
              .service("/", (req, ctx) -> HttpResponse.of("Hello!"));

            // Record when the method triggered
            final ServerListener sl =
                    ServerListener.builder()
                                  // add a callback.
                                  .whenStarting((Server server) -> STARTING_AT = System.currentTimeMillis())
                                  // add multiple callbacks, one by one.
                                  .whenStarted((Server server) -> STARTED_AT = -1)
                                  .whenStarted((Server server) -> STARTED_AT = System.currentTimeMillis())
                                  // add multiple callbacks at once, with vargs api.
                                  .whenStopping((Server server) -> STOPPING_AT = System.currentTimeMillis(),
                                                (Server server) -> STARTING_AT = 0L)
                                  // add multiple callbacks at once, with iterable api.
                                  .whenStopped(Lists.newArrayList(
                                          (Server server) -> STOPPED_AT = System.currentTimeMillis(),
                                          (Server server) -> STARTED_AT = 0L))
                                  .build();
            sb.serverListener(sl);
        }
    };

    @Test
    void testServerListener() throws Exception {
        // Before stop
        assertThat(STARTING_AT).isGreaterThan(0L);
        assertThat(STARTED_AT).isGreaterThanOrEqualTo(STARTING_AT);
        assertThat(STOPPING_AT).isEqualTo(0L);
        assertThat(STOPPED_AT).isEqualTo(0L);

        final Server server = ServerListenerTest.server.server();
        server.stop().get();

        // After stop
        assertThat(STARTING_AT).isEqualTo(0L);
        assertThat(STARTED_AT).isEqualTo(0L);
        assertThat(STOPPING_AT).isGreaterThanOrEqualTo(0L);
        assertThat(STOPPED_AT).isGreaterThanOrEqualTo(STOPPING_AT);
    }

    @Test
    void serverStartingExceptionAbortsStartup() {
        final Exception ex = new RuntimeException();
        final Server server = Server
                .builder()
                .service("/", (ctx, req) -> HttpResponse.of(200))
                .serverListener(new ServerListenerAdapter() {
                    @Override
                    public void serverStarting(Server server) throws Exception {
                        throw ex;
                    }
                })
                .build();
        assertThatThrownBy(() -> server.start().join())
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCause(ex);
    }

    @ParameterizedTest(autoCloseArguments = false)
    @ArgumentsSource(WaitIndefinitelyProvider.class)
    @Timeout(5)
    void testGracefulShutdownAndWaitIndefinitelyOnServerStopping(
            ExecutorService executor,
            boolean expected
    ) throws Exception {
        final ServerListener sl = ServerListener.builder()
                                                .stoppingWithExecutor(executor)
                                                .build();

        final Server server = Server.builder()
                                    .service("/", (req, ctx) -> HttpResponse.of("Hello!"))
                                    .serverListener(sl)
                                    .build();
        server.start().get();
        final CompletableFuture<Void> f = server.stop();
        Thread.sleep(3000);

        assertThat(executor.isTerminated()).isEqualTo(expected);
        // 작동하지 않음. 2번째 테스트에서 server가 종료되지 않으므로 락을 잡고 있다. 따라서 3번째 테스트의 서버가 시작되지 못한다.
        // InterruptException을 일으켜서 종료할 방법도 없다.
        f.get();
    }

    @ParameterizedTest
    @ArgumentsSource(WaitWithTimeoutProvider.class)
    void testExecutorServiceGracefulShutdownAndWaitWithTimeoutOnServerStop(
            ExecutorService executor,
            boolean expected
    ) throws Exception {
        final ServerListener sl = ServerListener.builder()
                                                .stoppingWithExecutor(executor, 1, TimeUnit.SECONDS)
                                                .build();

        final Server server = Server.builder()
                                    .service("/", (req, ctx) -> HttpResponse.of("Hello!"))
                                    .serverListener(sl)
                                    .build();
        server.start().get();
        server.stop();
        Thread.sleep(3000);

        assertThat(executor.isTerminated()).isEqualTo(expected);
    }

    private static ExecutorService[] executors() {
        final ScheduledExecutorService shortRunning = Executors.newSingleThreadScheduledExecutor();
        shortRunning.scheduleAtFixedRate(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 0, 1000, TimeUnit.MILLISECONDS);
        // It will be terminated by calling `shutdownNow()`.
        final ExecutorService interruptible = Executors.newSingleThreadExecutor();
        interruptible.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                final int foo = 42;
            }
        });
        // It cannot be terminated.
        final ExecutorService unstoppable = Executors.newSingleThreadExecutor();
        unstoppable.submit(() -> {
            while (true) {
                final int foo = 42;
            }
        });
        return new ExecutorService[] { shortRunning, interruptible, unstoppable };
    }

    private static class WaitIndefinitelyProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(final ExtensionContext context) throws Exception {
            final ExecutorService[] executors = executors();
            return Stream.of(
                    Arguments.of(executors[0], true),
                    Arguments.of(executors[1], false),
                    Arguments.of(executors[2], false));
        }
    }

    private static class WaitWithTimeoutProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(final ExtensionContext context) throws Exception {
            final ExecutorService[] executors = executors();
            return Stream.of(
                    Arguments.of(executors[0], true),
                    Arguments.of(executors[1], true),
                    Arguments.of(executors[2], false));
        }
    }
}
