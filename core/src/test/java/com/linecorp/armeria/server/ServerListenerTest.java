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

import org.assertj.core.util.Lists;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

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

    @Test
    void testGracefulShutdownExecutor() throws Exception {
        final CompletableFuture<Void> stopFuture;
        final CancellableExecutors executors = new CancellableExecutors();
        final ServerListener sl = ServerListener
                .builder()
                .shutdownWhenStopping(executors.periodicExecutors(0), 0)
                .shutdownWhenStopping(executors.interruptibleExecutors(0), 0)
                .shutdownWhenStopping(executors.uninterruptibleExecutors(0), 0)
                .shutdownWhenStopping(executors.periodicExecutors(1), 1000)
                .shutdownWhenStopping(executors.interruptibleExecutors(1), 1000)
                .shutdownWhenStopping(executors.uninterruptibleExecutors(1), 1000)
                .shutdownWhenStopped(executors.periodicExecutors(2), 0)
                .shutdownWhenStopped(executors.interruptibleExecutors(2), 0)
                .shutdownWhenStopped(executors.uninterruptibleExecutors(2), 0)
                .shutdownWhenStopped(executors.periodicExecutors(3), 1000)
                .shutdownWhenStopped(executors.interruptibleExecutors(3), 1000)
                .shutdownWhenStopped(executors.uninterruptibleExecutors(3), 1000)
                .build();

        final Server server = Server.builder()
                                    .service("/", (req, ctx) -> HttpResponse.of("Hello!"))
                                    .serverListener(sl)
                                    .build();
        server.start().get();
        stopFuture = server.stop();
        Thread.sleep(1000);

        for (int i = 0; i < 2; i++) {
            assertThat(executors.periodicExecutors(2 * i).isTerminated()).isEqualTo(true);
            executors.periodicTasks(2 * i).cancel();
            Thread.sleep(1000);

            assertThat(executors.interruptibleExecutors(2 * i).isTerminated()).isEqualTo(false);
            executors.interruptibleTasks(2 * i).cancel();
            Thread.sleep(1000);

            assertThat(executors.uninterruptibleExecutors(2 * i).isTerminated()).isEqualTo(false);
            executors.uninterruptibleTasks(2 * i).cancel();
            Thread.sleep(1000);

            assertThat(executors.periodicExecutors(2 * i + 1).isTerminated()).isEqualTo(true);
            executors.periodicTasks(2 * i + 1).cancel();
            Thread.sleep(1000);

            assertThat(executors.interruptibleExecutors(2 * i + 1).isTerminated()).isEqualTo(true);
            executors.interruptibleTasks(2 * i + 1).cancel();
            Thread.sleep(1000);

            assertThat(executors.uninterruptibleExecutors(2 * i + 1).isTerminated()).isEqualTo(false);
            executors.uninterruptibleTasks(2 * i + 1).cancel();
            Thread.sleep(4000);
        }
        stopFuture.get();
    }

    private abstract class CancellableRunnable implements Runnable {
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

    private final class CancellableExecutors {
        private static final int SIZE = 4;
        private final CancellableRunnable[] periodicTasks = new CancellableRunnable[SIZE];
        private final ExecutorService[] periodicExecutors = new ExecutorService[SIZE];
        private final CancellableRunnable[] interruptibleTasks = new CancellableRunnable[SIZE];
        private final ExecutorService[] interruptibleExecutors = new ExecutorService[SIZE];
        private final CancellableRunnable[] uninterruptibleTasks = new CancellableRunnable[SIZE];
        private final ExecutorService[] uninterruptibleExecutors = new ExecutorService[SIZE];

        CancellableExecutors() {
            for (int i = 0; i < SIZE; i++) {
                periodicTasks[i] = new Simple();
                interruptibleTasks[i] = new InterruptibleInfiniteLoop();
                uninterruptibleTasks[i] = new UninterruptibleInfiniteLoop();

                periodicExecutors[i] = Executors.newSingleThreadScheduledExecutor();
                periodicExecutors[i].submit(periodicTasks[i]);
                interruptibleExecutors[i] = Executors.newSingleThreadExecutor();
                interruptibleExecutors[i].submit(interruptibleTasks[i]);
                uninterruptibleExecutors[i] = Executors.newSingleThreadExecutor();
                uninterruptibleExecutors[i].submit(uninterruptibleTasks[i]);
            }
        }

        CancellableRunnable periodicTasks(int index) {
            return periodicTasks[index];
        }

        ExecutorService periodicExecutors(int index) {
            return periodicExecutors[index];
        }

        CancellableRunnable interruptibleTasks(int index) {
            return interruptibleTasks[index];
        }

        ExecutorService interruptibleExecutors(int index) {
            return interruptibleExecutors[index];
        }

        CancellableRunnable uninterruptibleTasks(int index) {
            return uninterruptibleTasks[index];
        }

        ExecutorService uninterruptibleExecutors(int index) {
            return uninterruptibleExecutors[index];
        }

        private final class Simple extends CancellableRunnable {
            @Override
            public void run() {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private final class InterruptibleInfiniteLoop extends CancellableRunnable {
            @Override
            public void run() {
                while (!isCanceled() && !Thread.currentThread().isInterrupted()) {
                    final int foo = 42;
                }
            }
        }

        private final class UninterruptibleInfiniteLoop extends CancellableRunnable {
            @Override
            public void run() {
                while (!isCanceled()) {
                    final int foo = 42;
                }
            }
        }
    }
}
