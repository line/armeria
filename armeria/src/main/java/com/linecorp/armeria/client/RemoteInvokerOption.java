/*
 * Copyright 2015 LINE Corporation
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
package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.function.Function;

import javax.net.ssl.TrustManagerFactory;

import com.linecorp.armeria.client.pool.KeyedChannelPoolHandler;
import com.linecorp.armeria.client.pool.PoolKey;
import com.linecorp.armeria.common.util.AbstractOption;

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.ConstantPool;

/**
 * A {@link RemoteInvoker} option.
 */
public class RemoteInvokerOption<T> extends AbstractOption<T> {

    @SuppressWarnings("rawtypes")
    private static final ConstantPool pool = new ConstantPool() {
        @Override
        protected RemoteInvokerOption<Object> newConstant(int id, String name) {
            return new RemoteInvokerOption<>(id, name);
        }
    };

    /**
     * The timeout of a socket connection attempt.
     */
    public static final RemoteInvokerOption<Duration> CONNECT_TIMEOUT = valueOf("CONNECT_TIMEOUT");

    /**
     * The idle timeout of a socket connection. The connection is closed if there is no invocation in progress
     * for this amount of time.
     */
    public static final RemoteInvokerOption<Duration> IDLE_TIMEOUT = valueOf("IDLE_TIMEOUT");

    /**
     * The maximum allowed length of the frame (or the content) decoded at the session layer. e.g. the
     * content of an HTTP request.
     */
    public static final RemoteInvokerOption<Integer> MAX_FRAME_LENGTH = valueOf("MAX_FRAME_LENGTH");

    /**
     * The maximum number of concurrent in-progress invocations.
     */
    public static final RemoteInvokerOption<Integer> MAX_CONCURRENCY = valueOf("MAX_CONCURRENCY");

    /**
     * The {@link TrustManagerFactory} of a TLS connection.
     */
    public static final RemoteInvokerOption<TrustManagerFactory> TRUST_MANAGER_FACTORY =
            valueOf("TRUST_MANAGER_FACTORY");

    /**
     * The {@link AddressResolverGroup} to use to resolve remote addresses into {@link InetSocketAddress}es.
     */
    public static final RemoteInvokerOption<AddressResolverGroup<? extends InetSocketAddress>>
            ADDRESS_RESOLVER_GROUP = valueOf("ADDRESS_RESOLVER_GROUP");

    /**
     * The {@link EventLoopGroup} that will provide the {@link EventLoop} for I/O and asynchronous invocations.
     * If unspecified, a new one is created automatically.
     */
    public static final RemoteInvokerOption<EventLoopGroup> EVENT_LOOP_GROUP = valueOf("EVENT_LOOP_GROUP");

    /**
     * The {@link Function} that decorates the {@link KeyedChannelPoolHandler}.
     */
    public static final RemoteInvokerOption<Function<KeyedChannelPoolHandler<PoolKey>,
            KeyedChannelPoolHandler<PoolKey>>> POOL_HANDLER_DECORATOR = valueOf("POOL_HANDLER_DECORATOR");

    /**
     * Whether to send an HTTP/2 preface string instead of an HTTP/1 upgrade request to negotiate the protocol
     * version of a cleartext HTTP connection.
     */
    public static final RemoteInvokerOption<Boolean> USE_HTTP2_PREFACE = valueOf("USE_HTTP2_PREFACE");

    /**
     * Returns the {@link RemoteInvokerOption} of the specified name.
     */
    @SuppressWarnings("unchecked")
    public static <T> RemoteInvokerOption<T> valueOf(String name) {
        return (RemoteInvokerOption<T>) pool.valueOf(name);
    }

    private RemoteInvokerOption(int id, String name) {
        super(id, name);
    }

    /**
     * Creates a new value of this option.
     */
    public RemoteInvokerOptionValue<T> newValue(T value) {
        requireNonNull(value, "value");
        return new RemoteInvokerOptionValue<>(this, value);
    }
}
