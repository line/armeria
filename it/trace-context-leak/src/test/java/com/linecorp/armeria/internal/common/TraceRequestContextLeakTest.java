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

package com.linecorp.armeria.internal.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.common.EventLoopGroupExtension;

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

class TraceRequestContextLeakTest {

    @RegisterExtension
    static final EventLoopExtension eventLoopExtension =
            new EventLoopExtension(ThreadFactories.newThreadFactory("trace-test", false));

    @RegisterExtension
    static final EventLoopGroupExtension eventLoopGroupExtension =
            new EventLoopGroupExtension(2, ThreadFactories.newThreadFactory("trace-test-group", false));

    @Test
    void singleThreadContextNotLeak() throws InterruptedException {
        final AtomicBoolean isThrown = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(2);

        final EventLoop executor =  eventLoopExtension.get();

        executor.execute(() -> {
            final ServiceRequestContext ctx = newCtx("/1");
            try (SafeCloseable ignore = ctx.push()) {
                // Ignore
            } catch (Exception ex) {
                isThrown.set(true);
            } finally {
                latch.countDown();
            }
        });

        executor.execute(() -> {
            final ServiceRequestContext anotherCtx = newCtx("/2");
            try (SafeCloseable ignore = anotherCtx.push()) {
                final ClientRequestContext clientCtx = newClientCtx("/3");
                try (SafeCloseable ignore2 = clientCtx.push()) {
                    assertThat(ClientRequestContext.current().unwrapAll()).isSameAs(clientCtx);
                }
            } catch (Exception ex) {
                isThrown.set(true);
            } finally {
                latch.countDown();
            }
        });

        assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();
        assertThat(isThrown).isFalse();
    }

    @Test
    @SuppressWarnings("MustBeClosedChecker")
    void singleThreadContextLeak() throws InterruptedException {
        final AtomicBoolean isThrown = new AtomicBoolean();
        final AtomicReference<Exception> exception = new AtomicReference<>();

        try (DeferredClose deferredClose = new DeferredClose()) {
            final EventLoop executor =  eventLoopExtension.get();

            executor.execute(() -> {
                final ServiceRequestContext ctx = newCtx("/1");
                final SafeCloseable leaked = ctx.push(); // <- Leaked, should show in error.
                deferredClose.add(executor, leaked);
            });

            executor.execute(() -> {
                final ServiceRequestContext anotherCtx = newCtx("/2");
                try (SafeCloseable ignore = anotherCtx.push()) {
                    // Ignore
                } catch (Exception ex) {
                    exception.set(ex);
                    isThrown.set(true);
                }
            });

            await().untilTrue(isThrown);
            assertThat(exception.get())
                    .hasMessageContaining("the callback was called from unexpected thread");
        }
    }

    @Test
    @SuppressWarnings("MustBeClosedChecker")
    void multiThreadContextLeakNotInterfereOthersEventLoop() throws InterruptedException {
        final AtomicBoolean isThrown = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(2);

        final EventLoopGroup executor =  eventLoopGroupExtension.get();

        final Executor ex1 = executor.next();
        final Executor ex2 = executor.next();

        try (DeferredClose deferredClose = new DeferredClose()) {
            ex1.execute(() -> {
                final ServiceRequestContext ctx = newCtx("/1");
                final SafeCloseable leaked = ctx.push();
                latch.countDown();
                deferredClose.add(executor, leaked);
            });

            ex2.execute(() -> {
                // Leak happened on the first eventLoop shouldn't affect 2nd eventLoop when trying to push
                await().until(() -> latch.getCount() == 1);
                final ServiceRequestContext anotherCtx = newCtx("/2");
                try (SafeCloseable ignore1 = anotherCtx.push()) {
                    final ClientRequestContext cctx = newClientCtx("/3");
                    try (SafeCloseable ignore2 = cctx.push()) {
                        // Ignore
                    }
                } catch (Exception ex) {
                    // Not suppose to throw exception on the second event loop
                    isThrown.set(true);
                } finally {
                    latch.countDown();
                }
            });

            assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();
            assertThat(isThrown).isFalse();
        }
    }

    @Test
    @SuppressWarnings("MustBeClosedChecker")
    void multiThreadContextLeak() throws InterruptedException {
        final AtomicBoolean isThrown = new AtomicBoolean(false);
        final AtomicReference<Exception> exception = new AtomicReference<>();
        final CountDownLatch waitForExecutor2 = new CountDownLatch(1);

        final EventLoopGroup executor =  eventLoopGroupExtension.get();

        final ServiceRequestContext leakingCtx = newCtx("/1-leak");
        final ServiceRequestContext anotherCtx2 = newCtx("/2-leak");
        final ServiceRequestContext anotherCtx3 = newCtx("/3-leak");

        final Executor ex1 = executor.next();
        final Executor ex2 = executor.next();

        try (DeferredClose deferredClose = new DeferredClose()) {
            ex1.execute(() -> {
                final SafeCloseable leaked = leakingCtx.push(); // <- Leaked, should show in error.
                deferredClose.add(ex1, leaked);
            });

            ex2.execute(() -> {
                try {
                    final SafeCloseable leaked = anotherCtx2.push();
                    deferredClose.add(ex2, leaked);
                } catch (Exception ex) {
                    isThrown.set(true);
                } finally {
                    waitForExecutor2.countDown();
                }
            });

            waitForExecutor2.await();
            assertThat(isThrown).isFalse();

            ex1.execute(() -> {
                try (SafeCloseable ignore = anotherCtx3.push()) {
                    // Ignore
                } catch (Exception ex) {
                    exception.set(ex);
                    isThrown.set(true);
                }
            });

            await().untilTrue(isThrown);
            assertThat(exception.get())
                    .hasMessageContaining("Trying to call object wrapped with context");
        }
    }

    @Test
    void pushIllegalServiceRequestContext() {
        final ServiceRequestContext sctx1 = newCtx("/1");
        final ServiceRequestContext sctx2 = newCtx("/2");
        try (SafeCloseable ignored = sctx1.push()) {
            assertThatThrownBy(sctx2::push).isInstanceOf(IllegalStateException.class)
                                           .hasMessageContaining("but context is currently set to");
        }
    }

    @Test
    void multipleRequestContextPushBeforeLeak() {
        final ServiceRequestContext sctx1 = newCtx("/1");
        final ServiceRequestContext sctx2 = newCtx("/2");
        try (SafeCloseable ignore1 = sctx1.push()) {
            final ClientRequestContext cctx1 = newClientCtx("/3");
            try (SafeCloseable ignore3 = cctx1.push()) {
                assertThatThrownBy(sctx2::push).isInstanceOf(IllegalStateException.class)
                                               .hasMessageContaining("but context is currently set to");
            }
        }
    }

    @Test
    @SuppressWarnings("MustBeClosedChecker")
    void cornerCase() {
        final AtomicReference<Exception> exception = new AtomicReference<>();

        try (DeferredClose deferredClose = new DeferredClose()) {
            final ServiceRequestContext ctx = newCtx("/1");
            try (SafeCloseable ignored = ctx.push()) {
                final ClientRequestContext ctx2 = newClientCtx("/2");
                ctx2.push(); // <- Leaked, should show in error.
                deferredClose.add(ctx2);
                final ClientRequestContext ctx3 = newClientCtx("/3");
                try (SafeCloseable ignored1 = ctx3.push()) {
                    // Ignore
                }
            } catch (Exception ex) {
                exception.set(ex);
            }
        }
        assertThat(exception.get())
                .hasMessageContaining("is not the same as the context in the storage");
    }

    private static ServiceRequestContext newCtx(String path) {
        return ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, path))
                                    .build();
    }

    private static ClientRequestContext newClientCtx(String path) {
        return ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, path))
                                   .build();
    }

    // Utility to clean up RequestContext leak after test
    private static class DeferredClose implements SafeCloseable {

        private final ConcurrentHashMap<Executor, SafeCloseable> toClose;
        private final Set<RequestContext> toRemoveFromThreadLocal;

        DeferredClose() {
            toClose = new ConcurrentHashMap<>();
            toRemoveFromThreadLocal = new HashSet<>();
        }

        void add(Executor executor, SafeCloseable closeable) {
            toClose.put(executor, closeable);
        }

        void add(RequestContext requestContext) {
            toRemoveFromThreadLocal.add(requestContext);
        }

        @Override
        public void close() {
            toClose.forEach((executor, closeable) -> executor.execute(closeable::close));
            toRemoveFromThreadLocal.forEach(ctx -> RequestContextUtil.pop(ctx, null));
        }
    }
}
