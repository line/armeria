/*
 * Copyright 2021 LINE Corporation
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

import static com.linecorp.armeria.common.stream.StreamMessageBenchmark.newStream;

import java.util.List;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import com.linecorp.armeria.common.stream.StreamMessageBenchmark.StreamObjects;

import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * Micro benchmarks of {@link StreamMessage#collect()}.
 */
@State(Scope.Benchmark)
public class StreamMessageCollectorBenchmark {

    @Benchmark
    public List<Integer> collect(StreamObjects streamObjects) {
        final StreamMessage<Integer> stream = newStream(streamObjects);
        streamObjects.writeAllValues(stream);
        return stream.collect().join();
    }

    @Benchmark
    public List<Integer> collectDirect(StreamObjects streamObjects) {
        final StreamMessage<Integer> stream = newStream(streamObjects);
        streamObjects.writeAllValues(stream);
        return stream.collect(ImmediateEventExecutor.INSTANCE).join();
    }

    @Benchmark
    public List<Integer> collectWithSubscriber(StreamObjects streamObjects) {
        final StreamMessage<Integer> stream = newStream(streamObjects);
        streamObjects.writeAllValues(stream);
        final StreamMessageCollector<Integer> collector = new StreamMessageCollector<>();
        stream.subscribe(collector);
        return collector.collect().join();
    }

    @Benchmark
    public List<Integer> collectWithSubscriberDirect(StreamObjects streamObjects) {
        final StreamMessage<Integer> stream = newStream(streamObjects);
        streamObjects.writeAllValues(stream);
        final StreamMessageCollector<Integer> collector = new StreamMessageCollector<>();
        stream.subscribe(collector, ImmediateEventExecutor.INSTANCE);
        return collector.collect().join();
    }
}
