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

package com.linecorp.armeria.internal.common.auth.oauth2;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class SerialFutureTest {

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void testExecuteAsync1(boolean withExecutor) throws Exception {
        final Executor executor = withExecutor ? Executors.newWorkStealingPool() : null;
        final SerialFuture serialFuture = new SerialFuture(executor);

        final int inc = 1;
        final int count = 20;
        final long timeout = 10L;
        final int[] seq = createArithmeticSequence(inc, inc, count);

        final VolatileCounter counter = new VolatileCounter();
        final IntFunction<CompletableFuture<Integer>> testAction = i ->
                serialFuture.executeAsync(() -> timeoutAction(counter, inc, timeout)).toCompletableFuture();
        final CompletableFuture<?>[] futures = Arrays.stream(seq)
                                                     .mapToObj(testAction)
                                                     .toArray(CompletableFuture[]::new);
        // wait for all futures to complete
        CompletableFuture.allOf(futures).join();

        assertThat(counter.get()).isEqualTo(count);
        final int[] array = Arrays.stream(futures).mapToInt(future -> (Integer) future.join())
                                  .toArray();
        assertThat(array).containsExactly(seq);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void testExecuteAsyncInParallel1(boolean withExecutor) throws Exception {
        final Executor executor = withExecutor ? Executors.newWorkStealingPool() : null;
        final SerialFuture serialFuture = new SerialFuture(executor);

        final int inc = 1;
        final int count = 20;
        final long timeout = 10L;
        final int[] seq = createArithmeticSequence(inc, inc, count);

        final VolatileCounter counter = new VolatileCounter();
        // test action that allows invoking serialFuture#callAsync(Callable) in parallel
        // use Executor as part of SerialFuture instance
        final Callable<CompletableFuture<Integer>> testAction = () ->
                serialFuture.executeAsync(() -> timeoutAction(counter, inc, timeout)).toCompletableFuture();
        final List<Callable<CompletableFuture<Integer>>> testActions = Arrays.stream(seq)
                                                                             .mapToObj(i -> testAction)
                                                                             .collect(Collectors.toList());
        // invoke test actions in parallel
        final ForkJoinPool pool = new ForkJoinPool(count);
        final CompletableFuture<?>[] futures = pool.invokeAll(testActions).parallelStream().map(f -> {
            try {
                return f.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }).toArray(CompletableFuture[]::new);
        // wait for all futures to complete
        CompletableFuture.allOf(futures).join();

        assertThat(counter.get()).isEqualTo(count);
        final int[] array = Arrays.stream(futures)
                                  .mapToInt(future -> (Integer) future.join())
                                  .toArray();
        System.out.println(Arrays.toString(array));
        assertThat(array).containsExactlyInAnyOrder(seq);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void testExecuteAsync2(boolean withExecutor) throws Exception {
        final Executor executor = Executors.newWorkStealingPool();
        final SerialFuture serialFuture = new SerialFuture(withExecutor ? executor : null);

        final int inc = 1;
        final int count = 20;
        final long timeout = 10L;
        final int[] seq = createArithmeticSequence(inc, inc, count);

        final VolatileCounter counter = new VolatileCounter();
        final IntFunction<CompletableFuture<Integer>> testAction = i ->
                serialFuture.executeAsync(() ->
                                                  CompletableFuture.supplyAsync(() ->
                                                                                        simpleTimeoutAction(
                                                                                                counter, inc,
                                                                                                timeout),
                                                                                executor))
                            .toCompletableFuture();
        final CompletableFuture<?>[] futures = Arrays.stream(seq)
                                                     .mapToObj(testAction)
                                                     .toArray(CompletableFuture[]::new);
        // wait for all futures to complete
        CompletableFuture.allOf(futures).join();

        assertThat(counter.get()).isEqualTo(count);
        final int[] array = Arrays.stream(futures).mapToInt(future -> (Integer) future.join())
                                  .toArray();
        assertThat(array).containsExactly(seq);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void testExecuteAsyncInParallel2(boolean withExecutor) throws Exception {
        final Executor executor = Executors.newWorkStealingPool();
        final SerialFuture serialFuture = new SerialFuture(withExecutor ? executor : null);

        final int inc = 1;
        final int count = 20;
        final long timeout = 10L;
        final int[] seq = createArithmeticSequence(inc, inc, count);

        final VolatileCounter counter = new VolatileCounter();
        // test action that allows invoking serialFuture#callAsync(Callable) in parallel
        // use Executor as part of SerialFuture instance
        final Callable<CompletableFuture<Integer>> testAction = () ->
                serialFuture.executeAsync(() ->
                                                  CompletableFuture.supplyAsync(() ->
                                                                                        simpleTimeoutAction(
                                                                                                counter, inc,
                                                                                                timeout),
                                                                                executor))
                            .toCompletableFuture();
        final List<Callable<CompletableFuture<Integer>>> testActions = Arrays.stream(seq)
                                                                             .mapToObj(i -> testAction)
                                                                             .collect(Collectors.toList());
        // invoke test actions in parallel
        final ForkJoinPool pool = new ForkJoinPool(count);
        final CompletableFuture<?>[] futures = pool.invokeAll(testActions).parallelStream().map(f -> {
            try {
                return f.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }).toArray(CompletableFuture[]::new);
        // wait for all futures to complete
        CompletableFuture.allOf(futures).join();

        assertThat(counter.get()).isEqualTo(count);
        final int[] array = Arrays.stream(futures).mapToInt(future -> (Integer) future.join())
                                  .toArray();
        System.out.println(Arrays.toString(array));
        assertThat(array).containsExactlyInAnyOrder(seq);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void testCallAsync(boolean withExecutor) throws Exception {
        final Executor executor = withExecutor ? Executors.newWorkStealingPool() : null;
        final SerialFuture serialFuture = new SerialFuture(executor);

        final int inc = 1;
        final int count = 20;
        final long timeout = 10L;
        final int[] seq = createArithmeticSequence(inc, inc, count);

        final VolatileCounter counter = new VolatileCounter();
        final IntFunction<CompletableFuture<Integer>> testAction = i ->
                serialFuture.callAsync(() -> simpleTimeoutAction(counter, inc, timeout)).toCompletableFuture();
        final CompletableFuture<?>[] futures = Arrays.stream(seq)
                                                     .mapToObj(testAction)
                                                     .toArray(CompletableFuture[]::new);
        // wait for all futures to complete
        CompletableFuture.allOf(futures).join();

        assertThat(counter.get()).isEqualTo(count);
        final int[] array = Arrays.stream(futures).mapToInt(future -> (Integer) future.join())
                                  .toArray();
        assertThat(array).containsExactly(seq);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void testCallAsyncInParallel(boolean withExecutor) throws Exception {
        final Executor executor = withExecutor ? Executors.newWorkStealingPool() : null;
        final SerialFuture serialFuture = new SerialFuture(executor);

        final int inc = 1;
        final int count = 20;
        final long timeout = 10L;
        final int[] seq = createArithmeticSequence(inc, inc, count);

        final VolatileCounter counter = new VolatileCounter();
        // test action that allows invoking serialFuture#callAsync(Callable) in parallel
        // use Executor as part of SerialFuture instance
        final Callable<CompletableFuture<Integer>> testAction = () ->
                serialFuture.callAsync(() -> simpleTimeoutAction(counter, inc, timeout)).toCompletableFuture();
        final List<Callable<CompletableFuture<Integer>>> testActions = Arrays.stream(seq)
                                                                             .mapToObj(i -> testAction)
                                                                             .collect(Collectors.toList());
        // invoke test actions in parallel
        final ForkJoinPool pool = new ForkJoinPool(count);
        final CompletableFuture<?>[] futures = pool.invokeAll(testActions).parallelStream().map(f -> {
            try {
                return f.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }).toArray(CompletableFuture[]::new);
        // wait for all futures to complete
        CompletableFuture.allOf(futures).join();

        assertThat(counter.get()).isEqualTo(count);
        final int[] array = Arrays.stream(futures).mapToInt(future -> (Integer) future.join())
                                  .toArray();
        System.out.println(Arrays.toString(array));
        assertThat(array).containsExactlyInAnyOrder(seq);
    }

    private static int simpleTimeoutAction(VolatileCounter counter, int inc, long timeout) {
        int c = counter.get();
        try {
            Thread.sleep(timeout);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        c += inc;
        counter.set(c);
        return c;
    }

    private static CompletionStage<Integer> timeoutAction(VolatileCounter counter, int inc, long timeout) {
        return CompletableFuture.completedFuture(simpleTimeoutAction(counter, inc, timeout));
    }

    private static int[] createArithmeticSequence(int start, int inc, int count) {
        final int[] seq = new int[count];
        int current = start;
        for (int i = 0; i < count; i++) {
            seq[i] = current;
            current += inc;
        }
        return seq;
    }

    private static final class VolatileCounter {
        volatile int counter;

        void set(int counter) {
            this.counter = counter;
        }

        int get() {
            return counter;
        }
    }
}
