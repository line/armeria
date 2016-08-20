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
 * An option that affects the session management of a {@link ClientFactory}.
 *
 * @param <T> the type of the option value
 */
public class SessionOption<T> extends AbstractOption<T> {

    @SuppressWarnings("rawtypes")
    private static final ConstantPool pool = new ConstantPool() {
        @Override
        protected SessionOption<Object> newConstant(int id, String name) {
            return new SessionOption<>(id, name);
        }
    };

    /**
     * The timeout of a socket connection attempt.
     */
    public static final SessionOption<Duration> CONNECT_TIMEOUT = valueOf("CONNECT_TIMEOUT");

    /**
     * The idle timeout of a socket connection. The connection is closed if there is no invocation in progress
     * for this amount of time.
     */
    public static final SessionOption<Duration> IDLE_TIMEOUT = valueOf("IDLE_TIMEOUT");

    /**
     * The {@link TrustManagerFactory} of a TLS connection.
     */
    public static final SessionOption<TrustManagerFactory> TRUST_MANAGER_FACTORY =
            valueOf("TRUST_MANAGER_FACTORY");

    /**
     * The {@link AddressResolverGroup} to use to resolve remote addresses into {@link InetSocketAddress}es.
     */
    public static final SessionOption<AddressResolverGroup<? extends InetSocketAddress>>
            ADDRESS_RESOLVER_GROUP = valueOf("ADDRESS_RESOLVER_GROUP");

    /**
     * The {@link EventLoopGroup} that will provide the {@link EventLoop} for I/O and asynchronous invocations.
     * If unspecified, a new one is created automatically.
     */
    public static final SessionOption<EventLoopGroup> EVENT_LOOP_GROUP = valueOf("EVENT_LOOP_GROUP");

    /**
     * The {@link Function} that decorates the {@link KeyedChannelPoolHandler}.
     */
    public static final SessionOption<Function<KeyedChannelPoolHandler<PoolKey>,
                KeyedChannelPoolHandler<PoolKey>>> POOL_HANDLER_DECORATOR = valueOf("POOL_HANDLER_DECORATOR");

    /**
     * Whether to send an HTTP/2 preface string instead of an HTTP/1 upgrade request to negotiate the protocol
     * version of a cleartext HTTP connection.
     */
    public static final SessionOption<Boolean> USE_HTTP2_PREFACE = valueOf("USE_HTTP2_PREFACE");

    /**
     * Returns the {@link SessionOption} of the specified name.
     */
    @SuppressWarnings("unchecked")
    public static <T> SessionOption<T> valueOf(String name) {
        return (SessionOption<T>) pool.valueOf(name);
    }

    private SessionOption(int id, String name) {
        super(id, name);
    }

    /**
     * Creates a new value of this option.
     */
    public SessionOptionValue<T> newValue(T value) {
        requireNonNull(value, "value");
        return new SessionOptionValue<>(this, value);
    }
}
