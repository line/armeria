package com.linecorp.armeria.client;

import io.netty.channel.EventLoopGroup;

import java.util.List;
import java.util.function.ToIntFunction;

@FunctionalInterface
public interface EventLoopSchedulerFactory {
    EventLoopScheduler newScheduler(EventLoopGroup eventLoopGroup,
                                    List<ToIntFunction<Endpoint>> maxNumEventLoopFunctions,
                                    int maxNumEventLoopsFunctions,
                                    int maxNumEventLoopsPerHttp1Endpoint,
                                    long idleTimeOutMillis);
}
