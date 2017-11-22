/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.benchmarks.core;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.benchmarks.shared.EventLoopJmhExecutor;
import com.linecorp.armeria.common.stream.DefaultStreamMessage;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;

@Fork(jvmArgsAppend = { EventLoopJmhExecutor.JVM_ARG_1, EventLoopJmhExecutor.JVM_ARG_2 })
@State(Scope.Benchmark)
public class StreamMessageBenchmark {

    private static final Logger logger = LoggerFactory.getLogger(StreamMessageBenchmark.class);

    private static final EventLoop ANOTHER_EVENT_LOOP = new DefaultEventLoop();

    @State(Scope.Thread)
    public static class StreamObjects {

        private final ArrayList<Integer> values = new ArrayList<>();

        @Param({ "3", "5", "20", "100", "1000" })
        private int num;

        private long sum;

        private SummingSubscriber subscriber;

        private CountDownLatch completedLatch;

        @Setup(Level.Invocation)
        public void setValues() {
            completedLatch = new CountDownLatch(1);
            values.clear();
            sum = 0;
            for (int i = 0; i < num; i++) {
                values.add(i);
                sum += i;
            }
            subscriber = new SummingSubscriber(completedLatch);
        }

        private long computedSum() {
            long computedSum = subscriber.sum();
            if (computedSum != sum) {
                throw new IllegalStateException(
                        "Did not compute the expected sum, the stream implementation is broken, expected: " +
                        sum + ", got: " + computedSum);
            }
            return computedSum;
        }
    }

    @TearDown(Level.Trial)
    public void closeEventLoop() {
        ANOTHER_EVENT_LOOP.shutdownGracefully().syncUninterruptibly();
    }

    @Benchmark
    public long defaultStreamMessage_noExecutor(StreamObjects streamObjects) {
        DefaultStreamMessage<Integer> stream = new DefaultStreamMessage<>();
        stream.subscribe(streamObjects.subscriber);
        streamObjects.values.forEach(stream::write);
        stream.close();
        // No executor, so sum will be updated inline.
        return streamObjects.computedSum();
    }

    // Isolates performance of stream operations, but requires the stream to execute events inline or it would
    // deadlock.
    @Benchmark
    public long defaultStreamMessage_jmhEventLoop(StreamObjects streamObjects) {
        DefaultStreamMessage<Integer> stream = new DefaultStreamMessage<>();
        stream.subscribe(streamObjects.subscriber, EventLoopJmhExecutor.currentEventLoop());
        streamObjects.values.forEach(stream::write);
        stream.close();
        return streamObjects.computedSum();
    }

    // Has synchronization overhead, but does not require the stream to execute events inline so can be used
    // to compare approaches.
    @Benchmark
    public long defaultStreamMessage_notJmhEventLoop(StreamObjects streamObjects) throws Exception {
        ANOTHER_EVENT_LOOP.execute(() -> {
            DefaultStreamMessage<Integer> stream = new DefaultStreamMessage<>();
            stream.subscribe(streamObjects.subscriber, ANOTHER_EVENT_LOOP);
            streamObjects.values.forEach(stream::write);
            stream.close();
        });
        streamObjects.completedLatch.await(10, TimeUnit.SECONDS);
        return streamObjects.computedSum();
    }

    private static final class SummingSubscriber implements Subscriber<Integer> {

        private final CountDownLatch completedLatch;

        private long sum;
        private boolean complete;
        private Throwable error;

        private SummingSubscriber(CountDownLatch completedLatch) {
            this.completedLatch = completedLatch;
        }

        private long sum() {
            if (!complete) {
                logger.warn("Stream not completed");
                return -1;
            }
            if (error != null) {
                logger.warn("Stream failed", error);
                return -2;
            }
            return sum;
        }

        @Override
        public void onSubscribe(Subscription s) {
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(Integer value) {
            sum += value;
        }

        @Override
        public void onError(Throwable t) {
            error = t;
            completedLatch.countDown();
        }

        @Override
        public void onComplete() {
            complete = true;
            completedLatch.countDown();
        }
    }
}
