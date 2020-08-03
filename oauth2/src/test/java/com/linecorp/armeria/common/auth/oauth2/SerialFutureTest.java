/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.common.auth.oauth2;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

public class SerialFutureTest {

    volatile int counter;

    @Test
    void testExecuteConcurrent1() throws Exception {
        final Executor executor = Executors.newWorkStealingPool();
        final SerialFuture serialFuture = new SerialFuture();

        final int n = 10;
        final long timeout = 10L;
        counter = 0;
        final CompletableFuture<?>[] futures = new CompletableFuture<?>[n];
        for (int i = 0; i < n; i++) {
            futures[i] = serialFuture.executeAsync(() ->
                CompletableFuture.supplyAsync(() -> {
                    int c = counter;
                    try {
                        Thread.sleep(timeout);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    c += 1;
                    counter = c;
                    return c;
                }, executor)
            ).toCompletableFuture();
        }
        CompletableFuture.allOf(futures).join();

        assertThat(counter).isEqualTo(n);
        final int[] array = Arrays.stream(futures).mapToInt(future -> (Integer) future.join()).toArray();
        checkSequentialRange(array, n);
    }

    @Test
    void testExecuteConcurrent2() throws Exception {
        final Executor executor = Executors.newWorkStealingPool();
        final SerialFuture serialFuture = new SerialFuture(executor);

        final int n = 10;
        final long timeout = 10L;
        counter = 0;
        final CompletableFuture<?>[] futures = new CompletableFuture<?>[n];
        for (int i = 0; i < n; i++) {
            futures[i] = serialFuture.executeAsync(() -> {
                int c = counter;
                try {
                    Thread.sleep(timeout);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                c += 1;
                counter = c;
                return CompletableFuture.completedFuture(c);
            }).toCompletableFuture();
        }
        CompletableFuture.allOf(futures).join();

        assertThat(counter).isEqualTo(n);
        final int[] array = Arrays.stream(futures).mapToInt(future -> (Integer) future.join()).toArray();
        checkSequentialRange(array, n);
    }

    @Test
    void testExecuteNonConcurrent() throws Exception {
        final SerialFuture serialFuture = new SerialFuture();

        final int n = 10;
        final long timeout = 10L;
        counter = 0;
        final CompletableFuture<?>[] futures = new CompletableFuture<?>[n];
        for (int i = 0; i < n; i++) {
            futures[i] = serialFuture.executeAsync(() -> {
                int c = counter;
                try {
                    Thread.sleep(timeout);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                c += 1;
                counter = c;
                return CompletableFuture.completedFuture(c);
            }).toCompletableFuture();
        }
        CompletableFuture.allOf(futures).join();

        assertThat(counter).isEqualTo(n);
        final int[] array = Arrays.stream(futures).mapToInt(future -> (Integer) future.join()).toArray();
        checkSequentialRange(array, n);
    }

    @Test
    void testCallConcurrent() throws Exception {
        final Executor executor = Executors.newWorkStealingPool();
        final SerialFuture serialFuture = new SerialFuture(executor);

        final int n = 10;
        final long timeout = 10L;
        counter = 0;
        final CompletableFuture<?>[] futures = new CompletableFuture<?>[n];
        for (int i = 0; i < n; i++) {
            futures[i] = serialFuture.callAsync(() -> {
                int c = counter;
                try {
                    Thread.sleep(timeout);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                c += 1;
                counter = c;
                return c;
            }).toCompletableFuture();
        }
        CompletableFuture.allOf(futures).join();

        assertThat(counter).isEqualTo(n);
        final int[] array = Arrays.stream(futures).mapToInt(future -> (Integer) future.join()).toArray();
        checkSequentialRange(array, n);
    }

    @Test
    void testCallNonConcurrent() throws Exception {
        final SerialFuture serialFuture = new SerialFuture();

        final int n = 10;
        final long timeout = 10L;
        counter = 0;
        final CompletableFuture<?>[] futures = new CompletableFuture<?>[n];
        for (int i = 0; i < n; i++) {
            futures[i] = serialFuture.callAsync(() -> {
                int c = counter;
                try {
                    Thread.sleep(timeout);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                c += 1;
                counter = c;
                return c;
            }).toCompletableFuture();
        }
        CompletableFuture.allOf(futures).join();

        assertThat(counter).isEqualTo(n);
        final int[] array = Arrays.stream(futures).mapToInt(future -> (Integer) future.join()).toArray();
        checkSequentialRange(array, n);
    }

    private static void checkSequentialRange(int[] array, int n) {
        // check that all elements of the array are unique numbers out of the range [1..n]
        assertThat(array.length).isEqualTo(n);
        final int[] expected = new int[n];
        for (int i = 0; i < n; i++) {
            expected[i] = i + 1;
        }
        assertThat(array).containsOnlyOnce(expected);
    }
}
