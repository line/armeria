/*
 * Copyright 2017 LINE Corporation
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
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import com.linecorp.armeria.common.stream.AbortedStreamException;

public class DefaultHttpResponseTest {

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    /**
     * The aggregation future must be completed even if the response being aggregated has been aborted.
     */
    @Test
    public void abortedAggregationWithoutExecutor() {
        final Thread mainThread = Thread.currentThread();
        final DefaultHttpResponse res = new DefaultHttpResponse();
        final CompletableFuture<AggregatedHttpMessage> future = res.aggregate();
        final AtomicReference<Thread> callbackThread = new AtomicReference<>();

        future.whenComplete((unused, cause) -> {
            callbackThread.set(Thread.currentThread());
            assertThat(cause).isInstanceOf(AbortedStreamException.class);
        });

        res.abort();
        assertThat(future).isCompletedExceptionally();
        assertThat(callbackThread.get()).isSameAs(mainThread);
    }

    /**
     * Same with {@link #abortedAggregationWithoutExecutor()} but with an {@link Executor}.
     */
    @Test
    public void abortedAggregationWithExecutor() {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Thread mainThread = Thread.currentThread();
        try {
            final DefaultHttpResponse res = new DefaultHttpResponse();
            final CompletableFuture<AggregatedHttpMessage> future = res.aggregate(executor);
            final AtomicReference<Thread> callbackThread = new AtomicReference<>();
            final AtomicReference<Throwable> callbackCause = new AtomicReference<>();

            future.whenComplete((unused, cause) -> {
                callbackCause.set(cause);
                callbackThread.set(Thread.currentThread());
            });

            res.abort();
            await().until(() -> callbackThread.get() != null);

            assertThat(callbackThread.get()).isNotSameAs(mainThread);
            assertThat(callbackCause.get()).isInstanceOf(AbortedStreamException.class);
            assertThat(future).isCompletedExceptionally();
        } finally {
            executor.shutdownNow();
        }
    }
}
