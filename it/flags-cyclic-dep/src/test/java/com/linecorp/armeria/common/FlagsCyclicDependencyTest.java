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

import static org.awaitility.Awaitility.await;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.internal.common.RequestContextUtil;

class FlagsCyclicDependencyTest {

    @Test
    void testRequestContextUtilAndFlagsCycle() throws InterruptedException {
        final AtomicInteger counter = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        final ExecutorService executorService = Executors.newFixedThreadPool(2);
        executorService.execute(() -> {
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            // Calls Flags.requestContextStorageProvider() internally when initializing RequestContextUtil.
            RequestContextUtil.get();
            counter.incrementAndGet();
        });

        executorService.execute(() -> {
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            // If RequestContextExportingAppender is enabled, when initializing Flags,
            // it calls RequestContextUtil.get() internally if FlagsLoaded doesn't work properly.
            Flags.requestContextStorageProvider();
            counter.incrementAndGet();
        });
        latch.countDown();
        await().until(() -> counter.get() == 2);
    }
}
