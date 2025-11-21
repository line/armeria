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

import static java.util.Objects.requireNonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.util.EventLoopGroups;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.grpc.Status;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;

/**
 * A builder for {@link XdsBootstrap}.
 */
@UnstableApi
public final class XdsBootstrapBuilder {

    private static final Logger logger = LoggerFactory.getLogger(XdsBootstrapBuilder.class);

    static final MeterIdPrefix DEFAULT_METER_ID_PREFIX = new MeterIdPrefix("armeria.xds");
    private static final EventLoopGroup DEFAULT_GROUP =
            EventLoopGroups.newEventLoopGroup(1, "xds-common-worker", true);

    private MeterRegistry meterRegistry = Flags.meterRegistry();
    private MeterIdPrefix meterIdPrefix = DEFAULT_METER_ID_PREFIX;
    private EventExecutor eventExecutor = DEFAULT_GROUP.next();
    private final Bootstrap bootstrap;
    private SnapshotWatcher<Object> snapshotWatcher = new SnapshotWatcher<Object>() {
        @Override
        public void snapshotUpdated(Object newSnapshot) {}

        @Override
        public void onError(XdsType type, String resourceName, Status status) {
            logger.warn("Error fetching resource '{}:{}' e: {}", type, resourceName, status);
        }
    };

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
        return new XdsBootstrapImpl(bootstrap, eventExecutor, meterIdPrefix, meterRegistry,
                                    ignored -> {}, snapshotWatcher);
    }
}
