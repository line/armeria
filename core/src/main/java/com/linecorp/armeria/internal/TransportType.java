package com.linecorp.armeria.internal;

import static com.linecorp.armeria.common.util.NativeLibraries.isEpollAvailable;
import static com.linecorp.armeria.common.util.NativeLibraries.isKqueueAvailable;

import java.util.concurrent.ThreadFactory;
import java.util.function.Function;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * Native transport types.
 */
public enum TransportType {
    NIO, EPOLL, KQUEUE;

    /**
     * Returns the {@link Class} of available {@link ServerChannel}.
     */
    public static Class<? extends ServerChannel> serverChannel() {
        final TransportType transportType = detectTransportType();
        switch (transportType) {
            case EPOLL:
                return EpollServerSocketChannel.class;
            case KQUEUE:
                return KQueueServerSocketChannel.class;
            case NIO:
                return NioServerSocketChannel.class;
            default:
                throw new Error("unsupported transport type:" + transportType);
        }
    }

    /**
     * Returns the available {@link EventLoopGroup}.
     */
    public static EventLoopGroup eventLoopGroup(int nThreads,
                                                Function<TransportType, ThreadFactory> threadFactoryFactory) {
        final TransportType transportType = detectTransportType();
        ThreadFactory threadFactory = threadFactoryFactory.apply(transportType);
        switch (transportType) {
            case EPOLL:
                return new EpollEventLoopGroup(nThreads, threadFactory);
            case KQUEUE:
                return new KQueueEventLoopGroup(nThreads, threadFactory);
            case NIO:
                return new NioEventLoopGroup(nThreads, threadFactory);
            default:
                throw new Error("unsupported transport type:" + transportType);
        }
    }

    private static TransportType detectTransportType() {
        if (isEpollAvailable()) {
            return EPOLL;
        } else if (isKqueueAvailable()) {
            return KQUEUE;
        } else {
            return NIO;
        }
    }
}
