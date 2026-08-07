/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.spring.xds;

import static java.util.Objects.requireNonNull;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.springframework.core.env.Environment;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.GeneratedMessageV3;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.XdsResourceReader;
import com.linecorp.armeria.xds.XdsType;
import com.linecorp.armeria.xds.configsource.InterestedResources;
import com.linecorp.armeria.xds.configsource.SotwConfigSourceSubscriptionFactory;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.spring.SpringConfigSource;
import com.linecorp.armeria.xds.stream.SnapshotStream;
import com.linecorp.armeria.xds.stream.Subscription;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;

/**
 * A {@link SotwConfigSourceSubscriptionFactory} that reads xDS resources from
 * Spring {@link Environment} properties. Each resource name is mapped to a
 * property key of the form {@code <prefix>.<resource-name>}, and the property
 * value is parsed as the type corresponding to the subscribed {@link XdsType}.
 *
 * <p>The property key prefix is configured via a {@link SpringConfigSource}
 * packed into the {@code typed_config} field of the bootstrap's
 * {@code custom_config_source}. The default prefixes are
 * {@code armeria.xds.listener.} for LDS and {@code armeria.xds.cluster.} for CDS.
 *
 * <p>Call {@link #refresh()} to re-read properties and push updated resources
 * to subscribers (e.g. from a Spring Cloud Config {@code EnvironmentChangeEvent}).
 *
 * @see SpringXdsAutoConfiguration
 */
@UnstableApi
public final class SpringConfigSourceFactory implements SotwConfigSourceSubscriptionFactory {

    private static final String NAME = "armeria.config_source.spring";

    private static final Object SIGNAL = new Object();

    private final Environment environment;
    private final RefreshSignal refreshSignal = new RefreshSignal();

    /**
     * Creates a new factory backed by the given Spring {@link Environment}.
     */
    public static SpringConfigSourceFactory of(Environment environment) {
        return new SpringConfigSourceFactory(environment);
    }

    private SpringConfigSourceFactory(Environment environment) {
        this.environment = requireNonNull(environment, "environment");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public SnapshotStream<DiscoveryResponse> create(ConfigSource configSource,
                                                    FactoryContext factoryContext,
                                                    SnapshotStream<InterestedResources> interestedResources) {
        final SpringConfigSource springConfigSource =
                factoryContext.validator().unpack(configSource.getCustomConfigSource().getTypedConfig(),
                                                  SpringConfigSource.class);
        final String rawPrefix = springConfigSource.getPrefix().trim();
        if (rawPrefix.isEmpty() || ".".equals(rawPrefix)) {
            throw new IllegalArgumentException(
                    "SpringConfigSource 'prefix' must not be empty. " +
                    "Set a prefix such as 'armeria.xds.' in the bootstrap typed_config.");
        }
        final String prefix = rawPrefix.endsWith(".") ? rawPrefix : rawPrefix + '.';
        final Map<XdsType, InterestedResources> accumulated = new EnumMap<>(XdsType.class);
        final SnapshotStream<Map<XdsType, InterestedResources>> allInterests =
                interestedResources.map(interest -> {
                    accumulated.put(interest.type(), interest);
                    return ImmutableMap.copyOf(accumulated);
                });
        final SnapshotStream<Object> refresh = refreshSignal.rescheduleEventsOn(factoryContext.eventLoop());
        return SnapshotStream.combineLatest(allInterests, refresh, (interests, signal) -> interests)
                             .switchMapEager(interests ->
                                                     buildResponseStream(environment, prefix, interests));
    }

    /**
     * Re-reads all active properties from the {@link Environment} and pushes
     * updated resources to subscribers.
     */
    public void refresh() {
        refreshSignal.push();
    }

    private static SnapshotStream<DiscoveryResponse> buildResponseStream(
            Environment environment, String prefix,
            Map<XdsType, InterestedResources> interests) {
        return watcher -> {
            for (InterestedResources interested : interests.values()) {
                try {
                    final DiscoveryResponse response = buildResponse(environment, prefix, interested);
                    watcher.onUpdate(response, null);
                } catch (Exception e) {
                    watcher.onUpdate(null, e);
                }
            }
            return Subscription.noop();
        };
    }

    private static DiscoveryResponse buildResponse(Environment environment, String prefix,
                                                   InterestedResources interested) {
        final XdsType type = interested.type();
        final String typeUrl = type.typeUrl();
        final Class<? extends GeneratedMessageV3> protoClass = type.resourceClass();
        final DiscoveryResponse.Builder builder = DiscoveryResponse.newBuilder()
                                                                   .setTypeUrl(typeUrl);
        for (String name : interested.resourceNames()) {
            final String propertyKey = prefix + name;
            final String yaml = environment.getProperty(propertyKey);
            if (yaml == null || yaml.isBlank()) {
                throw new IllegalArgumentException("Property '" + propertyKey + "' is empty or not set");
            }
            final GeneratedMessageV3 resource = XdsResourceReader.from(yaml, protoClass);
            builder.addResources(Any.pack(resource));
        }
        return builder.build();
    }

    static final class RefreshSignal implements SnapshotStream<Object> {

        private final Set<SnapshotWatcher<? super Object>> watchers = new CopyOnWriteArraySet<>();

        @Override
        public Subscription subscribe(SnapshotWatcher<? super Object> watcher) {
            watchers.add(watcher);
            watcher.onUpdate(SIGNAL, null);
            return () -> watchers.remove(watcher);
        }

        void push() {
            for (SnapshotWatcher<? super Object> w : watchers) {
                w.onUpdate(SIGNAL, null);
            }
        }
    }
}
