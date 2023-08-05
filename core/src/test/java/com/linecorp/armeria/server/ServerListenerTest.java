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

import org.assertj.core.util.Lists;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.common.util.SafeCloseable;
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

    @Test
    void testGracefulShutdownPeriodicExecutorWaitIndefinitelyOnServerStopping() throws Exception {
        final CompletableFuture<Void> stopFuture;
        try (CloseableRunnable task = new CloseableRunnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }) {
            final ScheduledExecutorService periodic = Executors.newSingleThreadScheduledExecutor();
            periodic.scheduleAtFixedRate(task, 0, 1000, TimeUnit.MILLISECONDS);
            final ServerListener sl = ServerListener.builder()
                                                    .stoppingWithExecutor(periodic)
                                                    .build();

            final Server server = Server.builder()
                                        .service("/", (req, ctx) -> HttpResponse.of("Hello!"))
                                        .serverListener(sl)
                                        .build();
            server.start().get();
            stopFuture = server.stop();
            Thread.sleep(3000);

            assertThat(periodic.isTerminated()).isEqualTo(true);
        }
        stopFuture.get();
    }

    @Test
    void testGracefulShutdownInterruptibleExecutorWaitIndefinitelyOnServerStopping() throws Exception {
        final CompletableFuture<Void> stopFuture;
        try (CloseableRunnable task = new CloseableRunnable() {
            @Override
            public void run() {
                while (!isClose() && !Thread.currentThread().isInterrupted()) {
                    final int foo = 42;
                }
            }
        }) {
            final ExecutorService interruptible = Executors.newSingleThreadExecutor();
            interruptible.submit(task);
            final ServerListener sl = ServerListener.builder()
                                                    .stoppingWithExecutor(interruptible)
                                                    .build();

            final Server server = Server.builder()
                                        .service("/", (req, ctx) -> HttpResponse.of("Hello!"))
                                        .serverListener(sl)
                                        .build();
            server.start().get();
            stopFuture = server.stop();
            Thread.sleep(3000);

            assertThat(interruptible.isTerminated()).isEqualTo(false);
        }
        stopFuture.get();
    }

    @Test
    void testGracefulShutdownUnstoppableExecutorWaitIndefinitelyOnServerStopping() throws Exception {
        final CompletableFuture<Void> stopFuture;
        try (CloseableRunnable task = new CloseableRunnable() {
            @Override
            public void run() {
                while (!isClose()) {
                    final int foo = 42;
                }
            }
        }) {
            final ExecutorService unstoppable = Executors.newSingleThreadExecutor();
            unstoppable.submit(task);
            final ServerListener sl = ServerListener.builder()
                                                    .stoppingWithExecutor(unstoppable)
                                                    .build();

            final Server server = Server.builder()
                                        .service("/", (req, ctx) -> HttpResponse.of("Hello!"))
                                        .serverListener(sl)
                                        .build();
            server.start().get();
            stopFuture = server.stop();
            Thread.sleep(3000);

            assertThat(unstoppable.isTerminated()).isEqualTo(false);
        }
        stopFuture.get();
    }

    @Test
    void testGracefulShutdownPeriodicExecutorWaitWithTimeoutOnServerStopping() throws Exception {
        final CompletableFuture<Void> stopFuture;
        try (CloseableRunnable task = new CloseableRunnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }) {
            final ScheduledExecutorService periodic = Executors.newSingleThreadScheduledExecutor();
            periodic.scheduleAtFixedRate(task, 0, 1000, TimeUnit.MILLISECONDS);
            final ServerListener sl = ServerListener.builder()
                                                    .stoppingWithExecutor(periodic, 1, TimeUnit.SECONDS)
                                                    .build();

            final Server server = Server.builder()
                                        .service("/", (req, ctx) -> HttpResponse.of("Hello!"))
                                        .serverListener(sl)
                                        .build();
            server.start().get();
            stopFuture = server.stop();
            Thread.sleep(3000);

            assertThat(periodic.isTerminated()).isEqualTo(true);
        }
        stopFuture.get();
    }

    @Test
    void testGracefulShutdownInterruptibleExecutorWaitWithTimeoutOnServerStopping() throws Exception {
        final CompletableFuture<Void> stopFuture;
        try (CloseableRunnable task = new CloseableRunnable() {
            @Override
            public void run() {
                while (!isClose() && !Thread.currentThread().isInterrupted()) {
                    final int foo = 42;
                }
            }
        }) {
            final ExecutorService interruptible = Executors.newSingleThreadExecutor();
            interruptible.submit(task);
            final ServerListener sl = ServerListener.builder()
                                                    .stoppingWithExecutor(interruptible, 1, TimeUnit.SECONDS)
                                                    .build();

            final Server server = Server.builder()
                                        .service("/", (req, ctx) -> HttpResponse.of("Hello!"))
                                        .serverListener(sl)
                                        .build();
            server.start().get();
            stopFuture = server.stop();
            Thread.sleep(3000);

            assertThat(interruptible.isTerminated()).isEqualTo(true);
        }
        stopFuture.get();
    }

    @Test
    void testGracefulShutdownUnstoppableExecutorWaitWithTimeoutOnServerStopping() throws Exception {
        final CompletableFuture<Void> stopFuture;
        try (CloseableRunnable task = new CloseableRunnable() {
            @Override
            public void run() {
                while (!isClose()) {
                    final int foo = 42;
                }
            }
        }) {
            final ExecutorService unstoppable = Executors.newSingleThreadExecutor();
            unstoppable.submit(task);
            final ServerListener sl = ServerListener.builder()
                                                    .stoppingWithExecutor(unstoppable, 1, TimeUnit.SECONDS)
                                                    .build();

            final Server server = Server.builder()
                                        .service("/", (req, ctx) -> HttpResponse.of("Hello!"))
                                        .serverListener(sl)
                                        .build();
            server.start().get();
            stopFuture = server.stop();
            Thread.sleep(3000);

            assertThat(unstoppable.isTerminated()).isEqualTo(false);
        }
        stopFuture.get();
    }

    private abstract static class CloseableRunnable implements SafeCloseable, Runnable {
        private volatile boolean close;

        CloseableRunnable() {
            close = false;
        }

        boolean isClose() {
            return close;
        }

        @Override
        public void close() {
            close = true;
        }
    }
}
