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
import java.util.stream.Stream;

import org.assertj.core.util.Lists;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.prometheus.PrometheusMeterRegistries;
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

    @ParameterizedTest
    @ArgumentsSource(CancellableExecutorsProvider.class)
    void testGracefulShutdownExecutorOnServerStopping(ExecutorService executor, CancellableRunnable task,
                                                      long timeout,
                                                      boolean isTerminationExpected) throws Exception {
        executor.submit(task);
        final CompletableFuture<Void> stopFuture;
        final ServerListener sl = ServerListener
                .builder()
                .shutdownWhenStopping(executor, timeout)
                .build();

        final Server server = Server.builder()
                                    .service("/", (req, ctx) -> HttpResponse.of("Hello!"))
                                    .serverListener(sl)
                                    .build();
        server.start().get();
        stopFuture = server.stop();
        int retryCnt = 4;
        while (retryCnt > 0 && isTerminationExpected && !executor.isTerminated()) {
            Thread.sleep(1000);
            retryCnt -= 1;
        }
        assertThat(executor.isTerminated()).isEqualTo(isTerminationExpected);
        task.cancel();
        stopFuture.get();
    }

    @ParameterizedTest
    @ArgumentsSource(CancellableExecutorsProvider.class)
    void testGracefulShutdownExecutorOnServerStopped(ExecutorService executor, CancellableRunnable task,
                                                     long timeout,
                                                     boolean isTerminationExpected) throws Exception {
        executor.submit(task);
        final CompletableFuture<Void> stopFuture;
        final ServerListener sl = ServerListener
                .builder()
                .shutdownWhenStopped(executor, timeout)
                .build();

        final Server server = Server.builder()
                                    .service("/", (req, ctx) -> HttpResponse.of("Hello!"))
                                    .serverListener(sl)
                                    .build();
        server.start().get();
        stopFuture = server.stop();
        int retryCnt = 4;
        while (retryCnt > 0 && isTerminationExpected && !executor.isTerminated()) {
            Thread.sleep(1000);
            retryCnt -= 1;
        }
        assertThat(executor.isTerminated()).isEqualTo(isTerminationExpected);
        task.cancel();
        stopFuture.get();
    }

    private static class CancellableExecutorsProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext extensionContext)
                throws Exception {
            return Stream.of(
                    Arguments.of(Executors.newSingleThreadScheduledExecutor(), new Simple(), 0, true),
                    Arguments.of(Executors.newSingleThreadExecutor(), new InterruptibleInfiniteLoop(), 0,
                                 false),
                    Arguments.of(Executors.newSingleThreadExecutor(), new UninterruptibleInfiniteLoop(), 0,
                                 false),
                    Arguments.of(Executors.newSingleThreadScheduledExecutor(), new Simple(), 100, true),
                    Arguments.of(Executors.newSingleThreadExecutor(), new InterruptibleInfiniteLoop(), 100,
                                 true),
                    Arguments.of(Executors.newSingleThreadExecutor(), new UninterruptibleInfiniteLoop(), 100,
                                 false)
            );
        }

        private static class Simple extends CancellableRunnable {
            @Override
            public void run() {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private static class InterruptibleInfiniteLoop extends CancellableRunnable {
            @Override
            public void run() {
                while (!isCanceled() && !Thread.currentThread().isInterrupted()) {
                    final int foo = 42;
                }
            }
        }

        private static class UninterruptibleInfiniteLoop extends CancellableRunnable {
            @Override
            public void run() {
                while (!isCanceled()) {
                    final int foo = 42;
                }
            }
        }
    }

    private abstract static class CancellableRunnable implements Runnable {
        private volatile boolean canceled;

        protected CancellableRunnable() {
            canceled = false;
        }

        public boolean isCanceled() {
            return canceled;
        }

        void cancel() {
            canceled = true;
        }
    }
}
