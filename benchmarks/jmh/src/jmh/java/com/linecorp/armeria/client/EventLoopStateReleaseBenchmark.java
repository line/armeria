package com.linecorp.armeria.client;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import com.google.common.collect.ImmutableList;

import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

@State(Scope.Benchmark)
public class EventLoopStateReleaseBenchmark {
    private AbstractEventLoopState state;

    @Param({"16"})
    private int maxNumEventLoops;
    @Param({"true", "false"})
    private boolean arrayBased;

    @Setup
    public void setUp() {
        try {
            final EventLoopGroup group = new DefaultEventLoopGroup(1024);
            final DefaultEventLoopScheduler s = new DefaultEventLoopScheduler(group,
                                                                              maxNumEventLoops, maxNumEventLoops,
                                                                              ImmutableList.of());
            final Field field = s.getClass().getDeclaredField("eventLoops");
            field.setAccessible(true);
            final List<EventLoop> eventLoops = (List<EventLoop>) field.get(s);
            if (arrayBased) {
                state = new ArrayBasedEventLoopState(eventLoops, maxNumEventLoops, s);
            } else {
                state = new HeapBasedEventLoopState(eventLoops, maxNumEventLoops, s);
            }

            IntStream.rangeClosed(0, maxNumEventLoops - 1)
                     .forEach(i -> state.acquire());
        } catch (Throwable x) {

        }
    }

    @TearDown
    public void tearDown() {
        try {
            final Field field = state.getClass().getDeclaredField("scheduler");
            field.setAccessible(true);
            field.set(state, null);

        } catch (Throwable x) {

        }
    }

    @Benchmark
    public void fifoAll() {
        releaseAll(IntStream.rangeClosed(0, maxNumEventLoops - 1)
                            .boxed()
                            .collect(Collectors.toList()));
    }

    @Benchmark
    public void lifoAll() {
        releaseAll(IntStream.rangeClosed(0, maxNumEventLoops - 1)
                            .map(x -> maxNumEventLoops - x - 1)
                            .boxed()
                            .collect(Collectors.toList()));
    }

    @Benchmark
    public void randomAll() {
        List<Integer> indices = IntStream.rangeClosed(0, maxNumEventLoops - 1)
                                        .boxed()
                                        .collect(Collectors.toList());
        Collections.shuffle(indices, ThreadLocalRandom.current());
        releaseAll(indices);
    }

    private void releaseAll(List<Integer> indices) {
        indices.forEach(i -> state.entries().get(i).release());
    }

    @Benchmark
    public void fifoOne() {
        releaseOne(0);
    }

    @Benchmark
    public void lifoOne() {
        releaseOne(maxNumEventLoops - 1);
    }

    @Benchmark
    public void randomOne() {
        releaseOne(ThreadLocalRandom.current().nextInt(maxNumEventLoops));
    }

    private void releaseOne(int index) {
        state.entries().get(index).release();
    }
}
