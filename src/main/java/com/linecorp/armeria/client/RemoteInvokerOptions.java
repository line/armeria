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

import static com.linecorp.armeria.client.RemoteInvokerOption.CONNECT_TIMEOUT;
import static com.linecorp.armeria.client.RemoteInvokerOption.EVENT_LOOP_GROUP;
import static com.linecorp.armeria.client.RemoteInvokerOption.IDLE_TIMEOUT;
import static com.linecorp.armeria.client.RemoteInvokerOption.MAX_CONCURRENCY;
import static com.linecorp.armeria.client.RemoteInvokerOption.MAX_FRAME_LENGTH;
import static com.linecorp.armeria.client.RemoteInvokerOption.POOL_HANDLER_DECORATOR;
import static com.linecorp.armeria.client.RemoteInvokerOption.TRUST_MANAGER_FACTORY;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import javax.net.ssl.TrustManagerFactory;

import com.linecorp.armeria.client.pool.KeyedChannelPoolHandler;
import com.linecorp.armeria.client.pool.PoolKey;
import com.linecorp.armeria.common.util.AbstractOptions;

import io.netty.channel.EventLoopGroup;

/**
 * A set of {@link RemoteInvokerOption}s and their respective values.
 */
public class RemoteInvokerOptions extends AbstractOptions {

    private static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofMillis(3200);
    private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_MAX_FRAME_LENGTH = 10485760; //10 MB
    private static final Integer DEFAULT_MAX_CONCURRENCY = Integer.MAX_VALUE;

    private static final RemoteInvokerOptionValue<?>[] DEFAULT_OPTION_VALUES = {
            CONNECT_TIMEOUT.newValue(DEFAULT_CONNECTION_TIMEOUT),
            IDLE_TIMEOUT.newValue(DEFAULT_IDLE_TIMEOUT),
            MAX_FRAME_LENGTH.newValue(DEFAULT_MAX_FRAME_LENGTH),
            MAX_CONCURRENCY.newValue(DEFAULT_MAX_CONCURRENCY)
    };

    /**
     * The default {@link RemoteInvokerOptions}.
     */
    public static final RemoteInvokerOptions DEFAULT = new RemoteInvokerOptions(DEFAULT_OPTION_VALUES);

    /**
     * Creates a new {@link RemoteInvokerOptions} with the specified {@link RemoteInvokerOptionValue}s.
     */
    public static RemoteInvokerOptions of(RemoteInvokerOptionValue<?>... options) {
        return new RemoteInvokerOptions(DEFAULT, options);
    }

    /**
     * Returns the {@link RemoteInvokerOptions} with the specified {@link RemoteInvokerOptionValue}s.
     */
    public static RemoteInvokerOptions of(Iterable<RemoteInvokerOptionValue<?>> options) {
        return new RemoteInvokerOptions(DEFAULT, options);
    }

    private static <T> RemoteInvokerOptionValue<T> validateValue(RemoteInvokerOptionValue<T> optionValue) {
        requireNonNull(optionValue, "value");

        RemoteInvokerOption<?> option = optionValue.option();
        T value = optionValue.value();

        if (option == CONNECT_TIMEOUT) {
            validateConnectionTimeout((Duration) value);
        } else if (option == MAX_FRAME_LENGTH) {
            validateMaxFrameLength((Integer) value);
        } else if (option == IDLE_TIMEOUT) {
            validateIdleTimeout((Duration) value);
        } else if (option == MAX_CONCURRENCY) {
            validateMaxConcurrency((Integer) value);
        }

        return optionValue;
    }

    private static int validateMaxFrameLength(int maxFrameLength) {
        if (maxFrameLength <= 0) {
            throw new IllegalArgumentException("maxFrameLength: " + maxFrameLength + " (expected: > 0)");
        }
        return maxFrameLength;
    }

    private static Duration validateConnectionTimeout(Duration connectionTimeout) {
        requireNonNull(connectionTimeout, "connectionTimeout");
        if (connectionTimeout.isNegative() || connectionTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "connectTimeout: " + connectionTimeout + " (expected: > 0)");
        }
        return connectionTimeout;
    }

    private static Duration validateIdleTimeout(Duration idleTimeout) {
        requireNonNull(idleTimeout, "idleTimeout");
        if (idleTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "idleTimeout: " + idleTimeout + " (expected: >= 0)");
        }
        return idleTimeout;
    }

    private static int validateMaxConcurrency(int maxConcurrency) {
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("maxConcurrency: " + maxConcurrency + " (expected: > 0)");
        }
        return maxConcurrency;
    }

    private RemoteInvokerOptions(RemoteInvokerOptionValue<?>... options) {
        super(RemoteInvokerOptions::validateValue, options);
    }

    private RemoteInvokerOptions(RemoteInvokerOptions baseOptions, RemoteInvokerOptionValue<?>... options) {
        super(RemoteInvokerOptions::validateValue, baseOptions, options);
    }

    private RemoteInvokerOptions(
            RemoteInvokerOptions baseOptions, Iterable<RemoteInvokerOptionValue<?>> options) {
        super(RemoteInvokerOptions::validateValue, baseOptions, options);
    }

    /**
     * Returns the value of the specified {@link RemoteInvokerOption}.
     *
     * @return the value of the {@link RemoteInvokerOption}, or
     *         {@link Optional#empty()} if the default value of the specified {@link RemoteInvokerOption} is
     *         not available
     */
    public <T> Optional<T> get(RemoteInvokerOption<T> option) {
        return get0(option);
    }

    /**
     * Returns the value of the specified {@link RemoteInvokerOption}.
     *
     * @return the value of the {@link RemoteInvokerOption}, or
     *         {@code defaultValue} if the specified {@link RemoteInvokerOption} is not set.
     */
    public <T> T getOrElse(RemoteInvokerOption<T> option, T defaultValue) {
        return getOrElse0(option, defaultValue);
    }

    /**
     * Converts this {@link RemoteInvokerOptions} to a {@link Map}.
     */
    public Map<RemoteInvokerOption<Object>, RemoteInvokerOptionValue<Object>> asMap() {
        return asMap0();
    }

    public Duration connectTimeout() {
        return getOrElse(CONNECT_TIMEOUT, DEFAULT_CONNECTION_TIMEOUT);
    }

    public long connectTimeoutMillis() {
        return connectTimeout().toMillis();
    }

    public Optional<EventLoopGroup> eventLoopGroup() {
        return get(EVENT_LOOP_GROUP);
    }

    public Optional<TrustManagerFactory> trustManagerFactory() {
        return get(TRUST_MANAGER_FACTORY);
    }

    public Duration idleTimeout() {
        return getOrElse(IDLE_TIMEOUT, DEFAULT_IDLE_TIMEOUT);
    }

    public long idleTimeoutMillis() {
        return idleTimeout().toMillis();
    }

    public int maxFrameLength() {
        return getOrElse(MAX_FRAME_LENGTH, DEFAULT_MAX_FRAME_LENGTH);
    }

    public int maxConcurrency() {
        return getOrElse(MAX_CONCURRENCY, DEFAULT_MAX_CONCURRENCY);
    }

    public Function<KeyedChannelPoolHandler<PoolKey>, KeyedChannelPoolHandler<PoolKey>> poolHandlerDecorator() {
        return getOrElse(POOL_HANDLER_DECORATOR, Function.identity());
    }
}
