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

package com.linecorp.armeria.client.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Tests that concurrent {@link EndpointGroup#selectNow} calls on a {@code StaticEndpointGroup}
 * always return a non-null {@link Endpoint}. This exercises the CAS race in
 * {@code AbstractEndpointSelector.tryInitialize()} where concurrent callers can see
 * {@code initialized == 1} but the load balancer has not been populated yet.
 */
class StaticEndpointGroupConcurrentSelectTest {

    @Test
    void concurrentSelectNowShouldNeverReturnNull() throws Exception {
        // Use 3 endpoints to ensure a StaticEndpointGroup is created (not a single Endpoint).
        final EndpointGroup endpointGroup = EndpointGroup.of(
                Endpoint.of("127.0.0.1", 8080),
                Endpoint.of("127.0.0.1", 8081),
                Endpoint.of("127.0.0.1", 8082));

        final int numThreads = 5;
        final CountDownLatch readyLatch = new CountDownLatch(numThreads);
        final CountDownLatch startLatch = new CountDownLatch(1);
        final List<Thread> threads = new ArrayList<>(numThreads);
        @SuppressWarnings("unchecked")
        final Endpoint[] results = new Endpoint[numThreads];
        final Exception[] errors = new Exception[numThreads];

        for (int i = 0; i < numThreads; i++) {
            final int index = i;
            final Thread thread = new Thread(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    errors[index] = e;
                    return;
                }
                final ClientRequestContext ctx =
                        ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
                results[index] = endpointGroup.selectNow(ctx);
            });
            threads.add(thread);
            thread.start();
        }

        // Wait for all threads to be ready, then release them simultaneously.
        assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
        startLatch.countDown();

        for (Thread thread : threads) {
            thread.join(5000);
        }

        for (int i = 0; i < numThreads; i++) {
            assertThat(errors[i]).as("Thread %d threw an exception", i).isNull();
            assertThat(results[i]).as("Thread %d got a null endpoint", i).isNotNull();
        }
    }
}
