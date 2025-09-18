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

import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.envoyproxy.envoy.config.core.v3.ConfigSource.ConfigSourceSpecifierCase;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

final class DefaultConfigSourceLifecycleObserver implements ConfigSourceLifecycleObserver {

    private static final Logger logger = LoggerFactory.getLogger(DefaultConfigSourceLifecycleObserver.class);

    private final String loggingIdentifier;
    private final MeterRegistry meterRegistry;

    private boolean active;
    private final Counter streamOpenedCounter;
    private final Counter streamErrorCounter;
    private final Counter streamCompletedCounter;
    private final Gauge streamActiveGauge;

    private final Counter streamRequestCounter;
    private final Counter streamResponseCounter;
    private final Counter resourceParseSuccessCounter;
    private final Counter resourceParseRejectedCounter;

    DefaultConfigSourceLifecycleObserver(MeterRegistry meterRegistry, MeterIdPrefix meterIdPrefix,
                                         ConfigSourceSpecifierCase specifierCase,
                                         String reprName, String xdsType) {
        this.meterRegistry = meterRegistry;
        loggingIdentifier = String.format("[%d.%s](%s)", specifierCase.getNumber(), xdsType, reprName);
        meterIdPrefix = meterIdPrefix.withTags("type", specifierCase.name().toLowerCase(Locale.ROOT),
                                               "name", reprName, "xdsType", xdsType);
        streamOpenedCounter = meterRegistry.counter(meterIdPrefix.name("configsource.stream.opened"),
                                                    meterIdPrefix.tags());
        streamErrorCounter = meterRegistry.counter(meterIdPrefix.name("configsource.stream.error"),
                                                   meterIdPrefix.tags());
        streamCompletedCounter = meterRegistry.counter(meterIdPrefix.name("configsource.stream.completed"),
                                                       meterIdPrefix.tags());

        streamRequestCounter = meterRegistry.counter(meterIdPrefix.name("configsource.stream.request"),
                                                     meterIdPrefix.tags());
        streamResponseCounter = meterRegistry.counter(meterIdPrefix.name("configsource.stream.response"),
                                                      meterIdPrefix.tags());

        resourceParseSuccessCounter =
                meterRegistry.counter(meterIdPrefix.name("configsource.resource.parse.success"),
                                      meterIdPrefix.tags());
        resourceParseRejectedCounter =
                meterRegistry.counter(meterIdPrefix.name("configsource.resource.parse.rejected"),
                                      meterIdPrefix.tags());

        streamActiveGauge = Gauge.builder(meterIdPrefix.name("configsource.stream.active"),
                                          () -> active ? 1d : 0d)
                                 .tags(meterIdPrefix.tags())
                                 .register(meterRegistry);
    }

    @Override
    public void streamOpened() {
        logger.debug("{} Stream opened.", loggingIdentifier);
        active = true;
        streamOpenedCounter.increment();
    }

    @Override
    public void streamError(Throwable throwable) {
        logger.debug("{} Stream closed: ", loggingIdentifier, throwable);
        active = false;
        streamErrorCounter.increment();
    }

    @Override
    public void streamCompleted() {
        logger.debug("{} Stream closed.", loggingIdentifier);
        active = false;
        streamCompletedCounter.increment();
    }

    @Override
    public void requestSent(DiscoveryRequest request) {
        logger.debug("{} Sending discovery request: {}", request, loggingIdentifier);
        streamRequestCounter.increment();
    }

    @Override
    public void responseReceived(DiscoveryResponse value) {
        if (logger.isTraceEnabled()) {
            logger.trace("{} Received discovery response: {}", value, loggingIdentifier);
        }
        streamResponseCounter.increment();
    }

    @Override
    public void resourceUpdated(XdsType type, DiscoveryResponse response,
                                Map<String, Object> updatedResources) {
        if (!updatedResources.isEmpty()) {
            logger.debug("{} Updating resources: {}", loggingIdentifier, updatedResources);
        }
        resourceParseSuccessCounter.increment(updatedResources.size());
    }

    @Override
    public void resourceRejected(XdsType type, DiscoveryResponse response,
                                 Map<String, Throwable> rejectedResources) {
        if (!rejectedResources.isEmpty()) {
            logger.warn("{} Rejected resources: {}", loggingIdentifier, rejectedResources);
        }
        resourceParseRejectedCounter.increment(rejectedResources.size());
    }

    @Override
    public void close() {
        meterRegistry.remove(streamOpenedCounter);
        meterRegistry.remove(streamErrorCounter);
        meterRegistry.remove(streamCompletedCounter);
        meterRegistry.remove(streamRequestCounter);
        meterRegistry.remove(streamResponseCounter);
        meterRegistry.remove(resourceParseSuccessCounter);
        meterRegistry.remove(resourceParseRejectedCounter);
        meterRegistry.remove(streamActiveGauge);
    }
}
