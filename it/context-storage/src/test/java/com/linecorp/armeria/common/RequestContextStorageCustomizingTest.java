/*
 *  Copyright 2020 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.CustomRequestContextStorageProvider.CustomRequestContextStorage;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.netty.channel.EventLoop;

class RequestContextStorageCustomizingTest {

    @RegisterExtension
    static final EventLoopExtension eventLoopExtension = new EventLoopExtension();

    @Test
    void requestContextStorageDoesNotAffectOtherThread() throws InterruptedException {
        final EventLoop eventLoop = eventLoopExtension.get();
        final ServiceRequestContext ctx = newCtx();

        final CountDownLatch latch1 = new CountDownLatch(1);
        final CountDownLatch latch2 = new CountDownLatch(1);
        final CountDownLatch latch3 = new CountDownLatch(1);
        try (SafeCloseable ignored = ctx.push()) {
            assertThat(CustomRequestContextStorageProvider.current()).isEqualTo(ctx);
            assertThat(CustomRequestContextStorageProvider.pushCalled()).isOne();

            eventLoop.execute(() -> {
                final ServiceRequestContext ctx1 = newCtx();
                try (SafeCloseable ignored1 = ctx1.push()) {
                    assertThat(CustomRequestContextStorageProvider.current()).isEqualTo(ctx1);
                    assertThat(CustomRequestContextStorageProvider.pushCalled()).isEqualTo(2);
                    latch1.countDown();
                    try {
                        latch2.await();
                    } catch (InterruptedException e) {
                        // ignore
                    }
                }
                assertThat(CustomRequestContextStorageProvider.current()).isNull();
                assertThat(CustomRequestContextStorageProvider.popCalled()).isEqualTo(2);
                latch3.countDown();
            });

            latch1.await();
            assertThat(CustomRequestContextStorageProvider.current()).isEqualTo(ctx);
            assertThat(CustomRequestContextStorageProvider.pushCalled()).isEqualTo(2);
        }
        assertThat(CustomRequestContextStorageProvider.current()).isNull();
        assertThat(CustomRequestContextStorageProvider.popCalled()).isOne();
        latch2.countDown();
        latch3.await();
    }

    @Test
    void hook() {
        final AtomicBoolean pushed = new AtomicBoolean();
        final AtomicBoolean popped = new AtomicBoolean();
        final AtomicBoolean got = new AtomicBoolean();

        RequestContextStorage.hook(delegate -> new RequestContextStorageWrapper(delegate) {
            @Nullable
            @Override
            public <T extends RequestContext> T push(RequestContext toPush) {
                pushed.set(true);
                return super.push(toPush);
            }

            @Override
            public void pop(RequestContext current, @Nullable RequestContext toRestore) {
                popped.set(true);
                super.pop(current, toRestore);
            }

            @Nullable
            @Override
            public <T extends RequestContext> T currentOrNull() {
                got.set(true);
                return super.currentOrNull();
            }
        });

        try {
            final ServiceRequestContext ctx = newCtx();

            assertThat(pushed).isFalse();
            assertThat(popped).isFalse();
            assertThat(got).isFalse();

            try (SafeCloseable ignored = ctx.push()) {
                assertThat(pushed).isTrue();
                assertThat(popped).isFalse();
                assertThat(got).isFalse();

                assertThat(ServiceRequestContext.current()).isSameAs(ctx);
                assertThat(popped).isFalse();
                assertThat(got).isTrue();
            }

            assertThat(popped).isTrue();
        } finally {
            RequestContextStorage.hook(storage -> storage.as(CustomRequestContextStorage.class));
        }
    }

    private static ServiceRequestContext newCtx() {
        return ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                    .build();
    }
}
