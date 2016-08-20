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

import static com.linecorp.armeria.client.SessionOption.ADDRESS_RESOLVER_GROUP;
import static com.linecorp.armeria.client.SessionOption.CONNECT_TIMEOUT;
import static com.linecorp.armeria.client.SessionOption.EVENT_LOOP_GROUP;
import static com.linecorp.armeria.client.SessionOption.IDLE_TIMEOUT;
import static com.linecorp.armeria.client.SessionOption.POOL_HANDLER_DECORATOR;
import static com.linecorp.armeria.client.SessionOption.TRUST_MANAGER_FACTORY;
import static com.linecorp.armeria.client.SessionOption.USE_HTTP2_PREFACE;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import javax.net.ssl.TrustManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.pool.KeyedChannelPoolHandler;
import com.linecorp.armeria.client.pool.PoolKey;
import com.linecorp.armeria.common.util.AbstractOptions;

import io.netty.channel.EventLoopGroup;
import io.netty.resolver.AddressResolverGroup;

/**
 * A set of {@link SessionOption}s and their respective values.
 */
public class SessionOptions extends AbstractOptions {

    private static final Logger logger = LoggerFactory.getLogger(SessionOptions.class);

    private static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofMillis(3200);
    private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(10);
    private static final Boolean DEFAULT_USE_HTTP2_PREFACE =
            "true".equals(System.getProperty("com.linecorp.armeria.defaultUseHttp2Preface", "false"));

    static {
        logger.info("defaultUseHttp2Preface: {}", DEFAULT_USE_HTTP2_PREFACE);
    }

    private static final SessionOptionValue<?>[] DEFAULT_OPTION_VALUES = {
            CONNECT_TIMEOUT.newValue(DEFAULT_CONNECTION_TIMEOUT),
            IDLE_TIMEOUT.newValue(DEFAULT_IDLE_TIMEOUT),
            USE_HTTP2_PREFACE.newValue(DEFAULT_USE_HTTP2_PREFACE)
    };

    /**
     * The default {@link SessionOptions}.
     */
    public static final SessionOptions DEFAULT = new SessionOptions(DEFAULT_OPTION_VALUES);

    /**
     * Creates a new {@link SessionOptions} with the specified {@link SessionOptionValue}s.
     */
    public static SessionOptions of(SessionOptionValue<?>... options) {
        return new SessionOptions(DEFAULT, options);
    }

    /**
     * Returns the {@link SessionOptions} with the specified {@link SessionOptionValue}s.
     */
    public static SessionOptions of(Iterable<SessionOptionValue<?>> options) {
        return new SessionOptions(DEFAULT, options);
    }

    private static <T> SessionOptionValue<T> validateValue(SessionOptionValue<T> optionValue) {
        requireNonNull(optionValue, "value");

        SessionOption<?> option = optionValue.option();
        T value = optionValue.value();

        if (option == CONNECT_TIMEOUT) {
            validateConnectionTimeout((Duration) value);
        } else if (option == IDLE_TIMEOUT) {
            validateIdleTimeout((Duration) value);
        }

        return optionValue;
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

    private SessionOptions(SessionOptionValue<?>... options) {
        super(SessionOptions::validateValue, options);
    }

    private SessionOptions(SessionOptions baseOptions, SessionOptionValue<?>... options) {
        super(SessionOptions::validateValue, baseOptions, options);
    }

    private SessionOptions(
            SessionOptions baseOptions, Iterable<SessionOptionValue<?>> options) {
        super(SessionOptions::validateValue, baseOptions, options);
    }

    /**
     * Returns the value of the specified {@link SessionOption}.
     *
     * @return the value of the {@link SessionOption}, or
     *         {@link Optional#empty()} if the default value of the specified {@link SessionOption} is
     *         not available
     */
    public <T> Optional<T> get(SessionOption<T> option) {
        return get0(option);
    }

    /**
     * Returns the value of the specified {@link SessionOption}.
     *
     * @return the value of the {@link SessionOption}, or
     *         {@code defaultValue} if the specified {@link SessionOption} is not set.
     */
    public <T> T getOrElse(SessionOption<T> option, T defaultValue) {
        return getOrElse0(option, defaultValue);
    }

    /**
     * Converts this {@link SessionOptions} to a {@link Map}.
     */
    public Map<SessionOption<Object>, SessionOptionValue<Object>> asMap() {
        return asMap0();
    }

    /**
     * Returns the {@link SessionOption#CONNECT_TIMEOUT} value.
     */
    public Duration connectTimeout() {
        return getOrElse(CONNECT_TIMEOUT, DEFAULT_CONNECTION_TIMEOUT);
    }

    /**
     * Returns the {@link SessionOption#CONNECT_TIMEOUT} value as milliseconds.
     */
    public long connectTimeoutMillis() {
        return connectTimeout().toMillis();
    }

    /**
     * Returns the {@link SessionOption#EVENT_LOOP_GROUP} if non-default was specified.
     */
    public Optional<EventLoopGroup> eventLoopGroup() {
        return get(EVENT_LOOP_GROUP);
    }

    /**
     * Returns the {@link SessionOption#TRUST_MANAGER_FACTORY} if non-default was specified.
     */
    public Optional<TrustManagerFactory> trustManagerFactory() {
        return get(TRUST_MANAGER_FACTORY);
    }

    /**
     * Returns the {@link SessionOption#ADDRESS_RESOLVER_GROUP} if non-default was specified.
     */
    public Optional<AddressResolverGroup<InetSocketAddress>> addressResolverGroup() {
        final Optional<AddressResolverGroup<? extends InetSocketAddress>> value = get(ADDRESS_RESOLVER_GROUP);

        @SuppressWarnings("unchecked")
        final Optional<AddressResolverGroup<InetSocketAddress>> castValue =
                (Optional<AddressResolverGroup<InetSocketAddress>>) (Optional<?>) value;

        return castValue;
    }

    /**
     * Returns the {@link SessionOption#IDLE_TIMEOUT} value.
     */
    public Duration idleTimeout() {
        return getOrElse(IDLE_TIMEOUT, DEFAULT_IDLE_TIMEOUT);
    }

    /**
     * Returns the {@link SessionOption#IDLE_TIMEOUT} value as milliseconds.
     */
    public long idleTimeoutMillis() {
        return idleTimeout().toMillis();
    }

    /**
     * Returns the {@link SessionOption#POOL_HANDLER_DECORATOR}.
     */
    public Function<KeyedChannelPoolHandler<PoolKey>, KeyedChannelPoolHandler<PoolKey>> poolHandlerDecorator() {
        return getOrElse(POOL_HANDLER_DECORATOR, Function.identity());
    }

    /**
     * Returns whether {@link SessionOption#USE_HTTP2_PREFACE} is enabled or not.
     */
    public boolean useHttp2Preface() {
        return getOrElse(USE_HTTP2_PREFACE, DEFAULT_USE_HTTP2_PREFACE);
    }
}
