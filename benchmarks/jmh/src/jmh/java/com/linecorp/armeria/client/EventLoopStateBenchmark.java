package com.linecorp.armeria.client;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
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
public class EventLoopStateBenchmark {
    private final int requestNumber = 100000;
    private AbstractEventLoopState state;

    @Param({"8, 16, 32, 64, 128"})
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

            // Allocate full entries in setup phase to reduce effect occurred when allocating new entry
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

            state.entries().clear();

        } catch (Throwable x) {

        }
    }

    @Benchmark
    public void acquireAndRelease() {
        List<AbstractEventLoopEntry> releaseOrder = IntStream.rangeClosed(1, requestNumber)
                                                             .mapToObj(i -> state.acquire())
                                                             .collect(Collectors.toList());
        Collections.reverse(releaseOrder);
        releaseOrder.forEach(AbstractEventLoopEntry::release);
    }
}
