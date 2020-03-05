/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.internal.common.zookeeper;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.CuratorFrameworkFactory.Builder;
import org.apache.curator.retry.ExponentialBackoffRetry;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;

public class AbstractCuratorFrameworkFactoryBuilder {

    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 1000;
    private static final int DEFAULT_SESSION_TIMEOUT_MILLIS = 10000;

    private static final ExponentialBackoffRetry DEFAULT_RETRY_POLICY =
            new ExponentialBackoffRetry(DEFAULT_CONNECT_TIMEOUT_MILLIS, 3);

    @Nullable
    private final CuratorFrameworkFactory.Builder clientBuilder;
    @Nullable
    private final ImmutableList.Builder<Consumer<? super Builder>> customizers;

    protected AbstractCuratorFrameworkFactoryBuilder(String zkConnectionStr) {
        checkArgument(!zkConnectionStr.isEmpty(), "zkConnectionStr can't be empty");
        clientBuilder = CuratorFrameworkFactory.builder()
                                               .connectString(zkConnectionStr)
                                               .connectionTimeoutMs(DEFAULT_CONNECT_TIMEOUT_MILLIS)
                                               .sessionTimeoutMs(DEFAULT_SESSION_TIMEOUT_MILLIS)
                                               .retryPolicy(DEFAULT_RETRY_POLICY);
        customizers = new ImmutableList.Builder<>();
    }

    protected AbstractCuratorFrameworkFactoryBuilder() {
        clientBuilder = null;
        customizers = null;
    }

    /**
     * Sets the specified connect timeout.
     * (default: {@value DEFAULT_CONNECT_TIMEOUT_MILLIS})
     *
     * @param connectTimeout the connect timeout
     *
     * @throws IllegalStateException if this builder was created with an existing {@link CuratorFramework}
     *                               instance.
     */
    public AbstractCuratorFrameworkFactoryBuilder connectTimeout(Duration connectTimeout) {
        requireNonNull(connectTimeout, "connectTimeout");
        checkArgument(!connectTimeout.isZero() && !connectTimeout.isNegative(),
                      "connectTimeout: %s (expected: > 0)", connectTimeout);
        return connectTimeoutMillis(connectTimeout.toMillis());
    }

    /**
     * Sets the specified connect timeout in milliseconds.
     * (default: {@value DEFAULT_CONNECT_TIMEOUT_MILLIS})
     *
     * @param connectTimeoutMillis the connect timeout in milliseconds
     *
     * @throws IllegalStateException if this builder was created with an existing {@link CuratorFramework}
     *                               instance.
     */
    public AbstractCuratorFrameworkFactoryBuilder connectTimeoutMillis(long connectTimeoutMillis) {
        checkArgument(connectTimeoutMillis > 0,
                      "connectTimeoutMillis: %s (expected: > 0)", connectTimeoutMillis);
        ensureInternalClient();
        customizer(builder -> builder.connectionTimeoutMs(Ints.saturatedCast(connectTimeoutMillis)));
        return this;
    }

    /**
     * Sets the session timeout.
     *
     * @param sessionTimeout the session timeout
     *
     * @throws IllegalStateException if this builder was created with an existing {@link CuratorFramework}
     *                               instance.
     */
    public AbstractCuratorFrameworkFactoryBuilder sessionTimeout(Duration sessionTimeout) {
        requireNonNull(sessionTimeout, "sessionTimeout");
        checkArgument(!sessionTimeout.isZero() && !sessionTimeout.isNegative(),
                      "sessionTimeout: %s (expected: > 0)", sessionTimeout);
        return sessionTimeoutMillis(sessionTimeout.toMillis());
    }

    /**
     * Sets the session timeout in milliseconds.
     * (default: {@value DEFAULT_SESSION_TIMEOUT_MILLIS})
     *
     * @param sessionTimeoutMillis the session timeout in milliseconds
     *
     * @throws IllegalStateException if this builder was created with an existing {@link CuratorFramework}
     *                               instance.
     */
    public AbstractCuratorFrameworkFactoryBuilder sessionTimeoutMillis(long sessionTimeoutMillis) {
        checkArgument(sessionTimeoutMillis > 0,
                      "sessionTimeoutMillis: %s (expected: > 0)", sessionTimeoutMillis);
        ensureInternalClient();
        customizer(builder -> builder.sessionTimeoutMs(Ints.saturatedCast(sessionTimeoutMillis)));
        return this;
    }

    /**
     * Specifies the {@link Consumer} that customizes the {@link CuratorFramework}.
     *
     * @throws IllegalStateException if this builder was created with an existing {@link CuratorFramework}
     *                               instance.
     */
    public AbstractCuratorFrameworkFactoryBuilder customizer(
            Consumer<? super CuratorFrameworkFactory.Builder> customizer) {
        ensureInternalClient();
        customizers.add(requireNonNull(customizer, "customizer"));
        return this;
    }

    private void ensureInternalClient() {
        checkState(clientBuilder != null,
                   "This method is allowed only when created with a connection string.");
    }

    protected final CuratorFramework buildCuratorFramework() {
        customizers.build().forEach(c -> c.accept(clientBuilder));
        return clientBuilder.build();
    }
}
