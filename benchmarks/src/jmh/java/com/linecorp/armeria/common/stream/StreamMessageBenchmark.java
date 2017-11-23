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

package com.linecorp.armeria.common.stream;

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

import com.linecorp.armeria.shared.EventLoopJmhExecutor;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;

@Fork(jvmArgsAppend = { EventLoopJmhExecutor.JVM_ARG_1, EventLoopJmhExecutor.JVM_ARG_2 })
@State(Scope.Benchmark)
public class StreamMessageBenchmark {

    private static final Logger logger = LoggerFactory.getLogger(StreamMessageBenchmark.class);

    private static final EventLoop ANOTHER_EVENT_LOOP = new DefaultEventLoop();

    @TearDown(Level.Trial)
    public void closeEventLoops() {
        ANOTHER_EVENT_LOOP.shutdownGracefully().syncUninterruptibly();
    }

    @State(Scope.Thread)
    public static class StreamObjects {

        public enum StreamType {
            DEFAULT_STREAM_MESSAGE,
            EVENT_LOOP_MESSAGE,
        }

        @Param
        private StreamType streamType;

        @Param({ "3", "5", "20", "100", "1000" })
        private int num;

        @Param({ "false", "true" })
        private boolean flowControl;

        private Integer[] values;

        private long sum;

        private SummingSubscriber subscriber;

        private CountDownLatch completedLatch;

        private CountDownLatch wroteLatch;

        private int writeIndex;

        @Setup(Level.Invocation)
        public void setValues() {
            completedLatch = new CountDownLatch(1);
            wroteLatch = new CountDownLatch(1);
            values = new Integer[num];
            sum = 0;
            writeIndex = 0;
            for (int i = 0; i < num; i++) {
                values[i] = i;
                sum += i;
            }
            subscriber = new SummingSubscriber(completedLatch, flowControl);
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

        private void writeAllValues(StreamWriter<Integer> stream) {
            for (Integer i : values) {
                stream.write(i);
            }
            stream.close();
            wroteLatch.countDown();
        }

        private void writeNextValue(StreamWriter<Integer> stream) {
            stream.write(values[writeIndex++]);
            if (writeIndex == values.length) {
                stream.close();
            }
        }
    }

    @Benchmark
    public long noExecutor(StreamObjects streamObjects) {
        StreamMessageAndWriter<Integer> stream = newStream(streamObjects);
        stream.subscribe(streamObjects.subscriber);
        streamObjects.writeAllValues(stream);
        stream.close();
        // No executor, so sum will be updated inline.
        return streamObjects.computedSum();
    }

    // Isolates performance of stream operations, but requires the stream to execute events inline or it would
    // deadlock.
    @Benchmark
    public long jmhEventLoop(StreamObjects streamObjects) {
        StreamMessageAndWriter<Integer> stream = newStream(streamObjects);
        stream.subscribe(streamObjects.subscriber, EventLoopJmhExecutor.currentEventLoop());
        streamObjects.writeAllValues(stream);
        stream.close();
        return streamObjects.computedSum();
    }

    // Has synchronization overhead, but does not require the stream to execute events inline so can be used
    // to compare approaches.
    @Benchmark
    public long notJmhEventLoop(StreamObjects streamObjects) throws Exception {
        ANOTHER_EVENT_LOOP.execute(() -> {
            StreamMessageAndWriter<Integer> stream = newStream(streamObjects);
            stream.subscribe(streamObjects.subscriber, ANOTHER_EVENT_LOOP);
            streamObjects.writeAllValues(stream);
            stream.close();
        });
        streamObjects.completedLatch.await(10, TimeUnit.SECONDS);
        return streamObjects.computedSum();
    }

    private StreamMessageAndWriter<Integer> newStream(StreamObjects streamObjects) {
        switch (streamObjects.streamType) {
            case EVENT_LOOP_MESSAGE:
                return new EventLoopStreamMessage<>(EventLoopJmhExecutor.currentEventLoop());
            case DEFAULT_STREAM_MESSAGE:
                return new DefaultStreamMessage<>();
            default:
                throw new Error();
        }
    }

    @Fork(jvmArgsAppend = { EventLoopJmhExecutor.JVM_ARG_1, EventLoopJmhExecutor.JVM_ARG_2 })
    @State(Scope.Benchmark)
    public static class StreamMessageThreadingBenchmark {

        private static final EventLoop EVENT_LOOP1 = new DefaultEventLoop();
        private static final EventLoop EVENT_LOOP2 = new DefaultEventLoop();
        private static final EventLoop EVENT_LOOP3 = new DefaultEventLoop();

        @TearDown(Level.Trial)
        public void closeEventLoops() {
            EVENT_LOOP1.shutdownGracefully().syncUninterruptibly();
            EVENT_LOOP2.shutdownGracefully().syncUninterruptibly();
            EVENT_LOOP3.shutdownGracefully().syncUninterruptibly();
        }

        // To evaluate performance with various threading models, we need to use multiple event loops with
        // synchronization. These benchmarks will not be useful for evaluating raw stream performance but can
        // compare different implementations under similar synchronization conditions.
        public enum EventLoopType {
            // Both reader and writer are the stream's event loop.
            READ_WRITE(EVENT_LOOP1, EVENT_LOOP1),
            // Only readers are the stream's event loop.
            READ_ONLY(EVENT_LOOP1, EVENT_LOOP2),
            // Only writers are the stream's event loop.
            WRITE_ONLY(EVENT_LOOP3, EVENT_LOOP1),
            // Neither are the stream's event loop.
            NONE(EVENT_LOOP3, EVENT_LOOP2);

            private final EventLoop readLoop;
            private final EventLoop writeLoop;

            EventLoopType(EventLoop readLoop, EventLoop writeLoop) {
                this.readLoop = readLoop;
                this.writeLoop = writeLoop;
            }
        }

        @Param
        private EventLoopType eventLoopType;

        @Benchmark
        public long writeFirst(StreamObjects streamObjects) throws Exception {
            StreamMessageAndWriter<Integer> stream = newStream(streamObjects);

            eventLoopType.writeLoop.execute(() -> streamObjects.writeAllValues(stream));
            streamObjects.wroteLatch.await(10, TimeUnit.SECONDS);

            stream.subscribe(streamObjects.subscriber, eventLoopType.readLoop);

            streamObjects.completedLatch.await(10, TimeUnit.SECONDS);

            return streamObjects.computedSum();
        }

        @Benchmark
        public long writeLast(StreamObjects streamObjects) throws Exception {
            StreamMessageAndWriter<Integer> stream = newStream(streamObjects);

            stream.subscribe(streamObjects.subscriber, eventLoopType.readLoop);

            eventLoopType.writeLoop.execute(() -> streamObjects.writeAllValues(stream));
            streamObjects.wroteLatch.await(10, TimeUnit.SECONDS);

            streamObjects.completedLatch.await(10, TimeUnit.SECONDS);

            return streamObjects.computedSum();
        }

        @Benchmark
        public long writeOnDemand(StreamObjects streamObjects) throws Exception {
            StreamMessageAndWriter<Integer> stream = newStream(streamObjects);
            stream.onDemand(() -> writeOnDemand(streamObjects, stream));

            stream.subscribe(streamObjects.subscriber, eventLoopType.readLoop);

            streamObjects.completedLatch.await(10, TimeUnit.SECONDS);

            return streamObjects.computedSum();
        }

        private void writeOnDemand(StreamObjects streamObjects, StreamMessageAndWriter<Integer> stream) {
            eventLoopType.writeLoop.submit(() -> {
                streamObjects.writeNextValue(stream);
                if (stream.isOpen()) {
                    stream.onDemand(() -> writeOnDemand(streamObjects, stream));
                }
            });
        }

        private StreamMessageAndWriter<Integer> newStream(StreamObjects streamObjects) {
            switch (streamObjects.streamType) {
                case EVENT_LOOP_MESSAGE:
                    return new EventLoopStreamMessage<>(EVENT_LOOP1);
                case DEFAULT_STREAM_MESSAGE:
                    return new DefaultStreamMessage<>();
                default:
                    throw new Error();
            }
        }
    }

    private static final class SummingSubscriber implements Subscriber<Integer> {

        private final CountDownLatch completedLatch;
        private final boolean flowControl;

        private Subscription subscription;

        private long sum;
        private boolean complete;
        private Throwable error;

        private SummingSubscriber(CountDownLatch completedLatch, boolean flowControl) {
            this.completedLatch = completedLatch;
            this.flowControl = flowControl;
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
        public void onSubscribe(Subscription subscription) {
            this.subscription = subscription;
            if (flowControl) {
                subscription.request(1);
            } else {
                subscription.request(Long.MAX_VALUE);
            }
        }

        @Override
        public void onNext(Integer value) {
            sum += value;
            if (flowControl) {
                subscription.request(1);
            }
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
