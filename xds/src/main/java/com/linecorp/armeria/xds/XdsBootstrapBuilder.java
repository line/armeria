/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds;

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;

/**
 * A builder for {@link XdsBootstrap}.
 */
@UnstableApi
public final class XdsBootstrapBuilder {

    static final MeterIdPrefix DEFAULT_METER_ID_PREFIX = new MeterIdPrefix("armeria.xds");
    private static final Logger logger = LoggerFactory.getLogger(XdsBootstrapBuilder.class);

    @Nullable
    private static EventLoopGroup defaultEventLoopGroup;
    private static final ReentrantShortLock DEFAULT_GROUP_LOCK = new ReentrantShortLock();

    private static EventLoopGroup defaultGroup() {
        final EventLoopGroup group = defaultEventLoopGroup;
        if (group != null) {
            return group;
        }
        DEFAULT_GROUP_LOCK.lock();
        try {
            final EventLoopGroup group0 = defaultEventLoopGroup;
            if (group0 != null) {
                return group0;
            }
            defaultEventLoopGroup = EventLoopGroups.newEventLoopGroup(1, "xds-common-worker", true);
            return defaultEventLoopGroup;
        } finally {
            DEFAULT_GROUP_LOCK.unlock();
        }
    }

    static final SnapshotWatcher<Object> DEFAULT_SNAPSHOT_WATCHER = new SnapshotWatcher<Object>() {

        @Override
        public void onUpdate(@Nullable Object snapshot, @Nullable XdsResourceException t) {
            if (t != null) {
                logger.warn("Error fetching resource e: ", t);
            }
        }
    };

    private MeterRegistry meterRegistry = Flags.meterRegistry();
    private MeterIdPrefix meterIdPrefix = DEFAULT_METER_ID_PREFIX;
    @Nullable
    private EventExecutor eventExecutor;
    private final Bootstrap bootstrap;
    private SnapshotWatcher<Object> snapshotWatcher = DEFAULT_SNAPSHOT_WATCHER;

    XdsBootstrapBuilder(Bootstrap bootstrap) {
        this.bootstrap = requireNonNull(bootstrap, "bootstrap");
    }

    /**
     * Sets the {@link MeterRegistry} used to collect metrics for this {@link XdsBootstrap}.
     */
    public XdsBootstrapBuilder meterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
        return this;
    }

    /**
     * Sets the {@link MeterIdPrefix} which will be applied to metrics emitted by this {@link XdsBootstrap}.
     */
    public XdsBootstrapBuilder meterIdPrefix(MeterIdPrefix meterIdPrefix) {
        this.meterIdPrefix = requireNonNull(meterIdPrefix, "meterIdPrefix");
        return this;
    }

    /**
     * Sets the {@link EventExecutor} used to synchronize internal state and invoke callbacks.
     */
    public XdsBootstrapBuilder eventExecutor(EventExecutor eventExecutor) {
        this.eventExecutor = requireNonNull(eventExecutor, "eventExecutor");
        return this;
    }

    /**
     * Sets the default {@link SnapshotWatcher} which is added to all
     * {@link ClusterRoot} and {@link ListenerRoot} by default.
     */
    public XdsBootstrapBuilder defaultSnapshotWatcher(SnapshotWatcher<Object> snapshotWatcher) {
        this.snapshotWatcher = requireNonNull(snapshotWatcher, "snapshotWatcher");
        return this;
    }

    /**
     * Builds the {@link XdsBootstrap}.
     */
    public XdsBootstrap build() {
        final EventExecutor eventExecutor = firstNonNull(this.eventExecutor, defaultGroup().next());
        return new XdsBootstrapImpl(bootstrap, eventExecutor, meterIdPrefix, meterRegistry,
                                    ignored -> {}, snapshotWatcher);
    }
}
