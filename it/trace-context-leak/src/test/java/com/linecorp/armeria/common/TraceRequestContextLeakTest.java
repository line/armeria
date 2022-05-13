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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.common.EventLoopGroupExtension;

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

class TraceRequestContextLeakTest {

    @RegisterExtension
    static final EventLoopExtension eventLoopExtension = new EventLoopExtension();

    @RegisterExtension
    static final EventLoopGroupExtension eventLoopGroupExtension = new EventLoopGroupExtension(2);

    @Test
    void singleThreadContextNotLeak() throws InterruptedException {
        final AtomicBoolean isThrown = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(2);

        final EventLoop executor =  eventLoopExtension.get();

        executor.execute(() -> {
            final ServiceRequestContext ctx = newCtx("/1");
            try (SafeCloseable ignore = ctx.push()) {
                //ignore
            } catch (Exception ex) {
                isThrown.set(true);
            } finally {
                latch.countDown();
            }
        });

        executor.execute(() -> {
            final ServiceRequestContext anotherCtx = newCtx("/2");
            try (SafeCloseable ignore = anotherCtx.push()) {
                //ignore
            } catch (Exception ex) {
                isThrown.set(true);
            } finally {
                latch.countDown();
            }
        });

        latch.await();
        assertThat(isThrown).isFalse();
    }

    @Test
    @SuppressWarnings("MustBeClosedChecker")
    void singleTreadContextLeak() throws InterruptedException {
        final AtomicBoolean isThrown = new AtomicBoolean();
        final AtomicReference<Exception> exception = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(2);

        try (DeferredClose deferredClose = new DeferredClose()) {
            final EventLoop executor =  eventLoopExtension.get();

            executor.execute(() -> {
                final ServiceRequestContext ctx = newCtx("/1");
                final SafeCloseable leaked = ctx.push();
                latch.countDown();
                deferredClose.add(executor, leaked);
            });

            executor.execute(() -> {
                final ServiceRequestContext anotherCtx = newCtx("/2");
                try (SafeCloseable ignore = anotherCtx.push()) {
                    //ignore
                } catch (Exception ex) {
                    isThrown.set(true);
                    exception.set(ex);
                } finally {
                    latch.countDown();
                }
            });

            latch.await();
            assertThat(isThrown).isTrue();
            assertThat(exception.get()).hasMessageContaining("RequestContext didn't popped");
        }
    }

    @Test
    @SuppressWarnings("MustBeClosedChecker")
    void multiThreadContextNotLeak() throws InterruptedException {
        final AtomicBoolean isThrown = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(2);

        final EventLoopGroup executor =  eventLoopGroupExtension.get();

        try (DeferredClose deferredClose = new DeferredClose()) {
            executor.next().execute(() -> {
                final ServiceRequestContext ctx = newCtx("/1");
                final SafeCloseable leaked = ctx.push();
                latch.countDown();
                deferredClose.add(executor, leaked);
            });

            executor.next().execute(() -> {
                //Leak happened on the first eventLoop shouldn't affect 2nd eventLoop when trying to push
                final ServiceRequestContext anotherCtx = newCtx("/2");
                try (SafeCloseable ignore = anotherCtx.push()) {
                    //Ignore
                } catch (Exception ex) {
                    //Not suppose to throw exception on the second event loop
                    isThrown.set(true);
                } finally {
                    latch.countDown();
                }
            });

            latch.await();
            assertThat(isThrown).isFalse();
        }
    }

    @Test
    @SuppressWarnings("MustBeClosedChecker")
    void multiThreadContextLeak() throws InterruptedException {
        final AtomicBoolean isThrown = new AtomicBoolean(false);
        final AtomicReference<Exception> exception = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(3);

        final EventLoopGroup executor =  eventLoopGroupExtension.get();

        final ServiceRequestContext ctx = newCtx("/1");
        final ServiceRequestContext anotherCtx = newCtx("/2");

        final Executor ex1 = executor.next();
        final Executor ex2 = executor.next();

        try (DeferredClose deferredClose = new DeferredClose()) {
            ex1.execute(() -> {
                final SafeCloseable leaked = ctx.push();
                latch.countDown();
                deferredClose.add(ex1, leaked);
            });

            ex2.execute(() -> {
                try {
                    //Intentional leak
                    final SafeCloseable leaked = anotherCtx.push();
                    deferredClose.add(ex2, leaked);
                } catch (Exception ex) {
                    isThrown.set(true);
                } finally {
                    latch.countDown();
                }
            });

            assertThat(isThrown).isFalse();

            ex1.execute(() -> {
                try (SafeCloseable ignore = anotherCtx.push()) {
                    //ignore
                } catch (Exception ex) {
                    isThrown.set(true);
                    exception.set(ex);
                } finally {
                    latch.countDown();
                }
            });

            latch.await();
            assertThat(isThrown).isTrue();
            assertThat(exception.get()).hasMessageContaining("RequestContext didn't popped");
        }
    }

    private static ServiceRequestContext newCtx(String path) {
        return ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, path))
                                    .build();
    }

    //Utility to clean up RequestContext leak after test
    private static class DeferredClose implements SafeCloseable {

        private final ConcurrentHashMap<Executor, SafeCloseable> toClose;

        DeferredClose() {
            toClose = new ConcurrentHashMap<>();
        }

        void add(Executor executor, SafeCloseable closeable) {
            toClose.put(executor, closeable);
        }

        @Override
        public void close() {
            toClose.forEach((executor, closeable) -> executor.execute(closeable::close));
        }
    }
}
