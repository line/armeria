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

package com.linecorp.armeria.common.zookeeper;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.function.Consumer;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.CuratorFrameworkFactory.Builder;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.common.PathUtils;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A skeletal builder implementation for {@link CuratorFramework}.
 */
@UnstableApi
public class AbstractCuratorFrameworkBuilder {

    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 1000;
    private static final int DEFAULT_SESSION_TIMEOUT_MILLIS = 10000;
    private static final ExponentialBackoffRetry DEFAULT_RETRY_POLICY =
            new ExponentialBackoffRetry(DEFAULT_CONNECT_TIMEOUT_MILLIS, 3);

    @Nullable
    private final CuratorFramework client;
    private final String znodePath;
    @Nullable
    private final CuratorFrameworkFactory.Builder clientBuilder;
    @Nullable
    private final ImmutableList.Builder<Consumer<? super Builder>> customizers;

    /**
     * Creates a new instance with the specified {@code zkConnectionStr}.
     */
    protected AbstractCuratorFrameworkBuilder(String zkConnectionStr, String znodePath) {
        requireNonNull(zkConnectionStr, "zkConnectionStr");
        checkArgument(!zkConnectionStr.isEmpty(), "zkConnectionStr can't be empty.");
        client = null;
        this.znodePath = validateZNodePath(znodePath);
        clientBuilder = CuratorFrameworkFactory.builder()
                                               .connectString(zkConnectionStr)
                                               .connectionTimeoutMs(DEFAULT_CONNECT_TIMEOUT_MILLIS)
                                               .sessionTimeoutMs(DEFAULT_SESSION_TIMEOUT_MILLIS)
                                               .retryPolicy(DEFAULT_RETRY_POLICY);
        customizers = ImmutableList.builder();
    }

    /**
     * Creates a new instance with the specified {@link CuratorFramework}.
     */
    protected AbstractCuratorFrameworkBuilder(CuratorFramework client, String znodePath) {
        this.client = requireNonNull(client, "client");
        this.znodePath = validateZNodePath(znodePath);
        clientBuilder = null;
        customizers = null;
    }

    private static String validateZNodePath(String znodePath) {
        try {
            PathUtils.validatePath(znodePath);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("znodePath: " + znodePath +
                                               " (reason: " + e.getMessage() + ')');
        }
        return znodePath;
    }

    /**
     * Returns the znode Path.
     */
    protected final String znodePath() {
        return znodePath;
    }

    /**
     * Sets the specified connect timeout. {@value DEFAULT_CONNECT_TIMEOUT_MILLIS} ms is used by default.
     *
     * @param connectTimeout the connect timeout
     *
     * @throws IllegalStateException if this builder was created with an existing {@link CuratorFramework}
     *                               instance.
     */
    public AbstractCuratorFrameworkBuilder connectTimeout(Duration connectTimeout) {
        requireNonNull(connectTimeout, "connectTimeout");
        checkArgument(!connectTimeout.isZero() && !connectTimeout.isNegative(),
                      "connectTimeout: %s (expected: > 0)", connectTimeout);
        return connectTimeoutMillis(connectTimeout.toMillis());
    }

    /**
     * Sets the specified connect timeout in milliseconds.
     * {@value DEFAULT_CONNECT_TIMEOUT_MILLIS} ms is used by default.
     *
     * @param connectTimeoutMillis the connect timeout in milliseconds
     *
     * @throws IllegalStateException if this builder was created with an existing {@link CuratorFramework}
     *                               instance.
     */
    public AbstractCuratorFrameworkBuilder connectTimeoutMillis(long connectTimeoutMillis) {
        checkArgument(connectTimeoutMillis > 0,
                      "connectTimeoutMillis: %s (expected: > 0)", connectTimeoutMillis);
        ensureInternalClient();
        customizer(builder -> builder.connectionTimeoutMs(Ints.saturatedCast(connectTimeoutMillis)));
        return this;
    }

    /**
     * Sets the session timeout. {@value DEFAULT_SESSION_TIMEOUT_MILLIS} ms is used by default.
     *
     * @param sessionTimeout the session timeout
     *
     * @throws IllegalStateException if this builder was created with an existing {@link CuratorFramework}
     *                               instance.
     */
    public AbstractCuratorFrameworkBuilder sessionTimeout(Duration sessionTimeout) {
        requireNonNull(sessionTimeout, "sessionTimeout");
        checkArgument(!sessionTimeout.isZero() && !sessionTimeout.isNegative(),
                      "sessionTimeout: %s (expected: > 0)", sessionTimeout);
        return sessionTimeoutMillis(sessionTimeout.toMillis());
    }

    /**
     * Sets the session timeout in milliseconds. {@value DEFAULT_SESSION_TIMEOUT_MILLIS} ms is used by default.
     *
     * @param sessionTimeoutMillis the session timeout in milliseconds
     *
     * @throws IllegalStateException if this builder was created with an existing {@link CuratorFramework}
     *                               instance.
     */
    public AbstractCuratorFrameworkBuilder sessionTimeoutMillis(long sessionTimeoutMillis) {
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
    public AbstractCuratorFrameworkBuilder customizer(
            Consumer<? super CuratorFrameworkFactory.Builder> customizer) {
        ensureInternalClient();
        customizers.add(requireNonNull(customizer, "customizer"));
        return this;
    }

    /**
     * Returns {@code true} if this builder is created with
     * {@link #AbstractCuratorFrameworkBuilder(CuratorFramework, String)}.
     */
    protected final boolean isUserSpecifiedCuratorFramework() {
       return client != null;
    }

    private void ensureInternalClient() {
        checkState(client == null,
                   "This method is allowed only when created with a connection string.");
    }

    /**
     * Returns a newly-created {@link CuratorFramework} based on the configuration properties added to
     * this builder if {@link #isUserSpecifiedCuratorFramework()} is true.
     * Otherwise, the {@link CuratorFramework} specified when creating this builder will be returned.
     *
     *
     */
    protected final CuratorFramework buildCuratorFramework() {
        if (client != null) {
            return client;
        }
        customizers.build().forEach(c -> c.accept(clientBuilder));
        return clientBuilder.build();
    }
}
