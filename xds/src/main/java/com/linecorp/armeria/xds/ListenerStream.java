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

import static com.linecorp.armeria.xds.XdsType.LISTENER;

import java.util.List;
import java.util.Optional;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.stream.RefCountedStream;
import com.linecorp.armeria.xds.stream.SnapshotStream;
import com.linecorp.armeria.xds.stream.Subscription;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;

final class ListenerStream extends RefCountedStream<ListenerSnapshot> {

    @Nullable
    private final ListenerXdsResource listenerXdsResource;
    private final String resourceName;
    private final SubscriptionContext context;

    ListenerStream(ListenerXdsResource listenerXdsResource, SubscriptionContext context) {
        this.listenerXdsResource = listenerXdsResource;
        resourceName = listenerXdsResource.name();
        this.context = context;
    }

    ListenerStream(String resourceName, SubscriptionContext context) {
        this.resourceName = resourceName;
        this.context = context;
        listenerXdsResource = null;
    }

    @Override
    protected Subscription onStart(SnapshotWatcher<ListenerSnapshot> watcher) {
        if (listenerXdsResource != null) {
            return resource2snapshot(listenerXdsResource, null).subscribe(watcher);
        }

        final ConfigSource configSource = context.configSourceMapper().ldsConfigSource();
        if (configSource == null) {
            final XdsResourceException e =
                    new XdsResourceException(LISTENER, resourceName, "config source not found");
            return SnapshotStream.<ListenerSnapshot>error(e)
                                 .subscribe(watcher);
        }
        return new ResourceNodeAdapter<ListenerXdsResource>(configSource, context, resourceName, LISTENER)
                .switchMapEager(resource -> resource2snapshot(resource, configSource))
                .subscribe(watcher);
    }

    private SnapshotStream<ListenerSnapshot> resource2snapshot(
            ListenerXdsResource resource, @Nullable ConfigSource parentConfigSource) {
        final ListenerFilterChainFactory factory = new ListenerFilterChainFactory(context, resource);

        // Resolve api_listener route (client-side path)
        final SnapshotStream<Optional<RouteSnapshot>> apiListenerRouteStream =
                factory.resolveApiListenerRoute(parentConfigSource);

        // Resolve filter chain snapshots (server-side path)
        final SnapshotStream<List<FilterChainSnapshot>> filterChainSnapshotsStream =
                resolveFilterChainSnapshots(resource, factory, parentConfigSource);
        final SnapshotStream<Optional<FilterChainSnapshot>> defaultFilterChainStream =
                resolveDefaultFilterChainSnapshot(resource, factory, parentConfigSource);

        return SnapshotStream.combineLatest(
                apiListenerRouteStream, filterChainSnapshotsStream, defaultFilterChainStream,
                (apiListenerRoute, filterChainSnapshots, defaultFilterChain) ->
                        new ListenerSnapshot(resource, apiListenerRoute,
                                             filterChainSnapshots, defaultFilterChain));
    }

    private static SnapshotStream<List<FilterChainSnapshot>> resolveFilterChainSnapshots(
            ListenerXdsResource resource, ListenerFilterChainFactory factory,
            @Nullable ConfigSource parentConfigSource) {
        final List<FilterChain> filterChains = resource.resource().getFilterChainsList();
        if (filterChains.isEmpty()) {
            return SnapshotStream.just(ImmutableList.of());
        }
        final ImmutableList.Builder<SnapshotStream<FilterChainSnapshot>> streams = ImmutableList.builder();
        for (FilterChain filterChain : filterChains) {
            streams.add(factory.resolve(filterChain, parentConfigSource));
        }
        return SnapshotStream.combineNLatest(streams.build());
    }

    private static SnapshotStream<Optional<FilterChainSnapshot>> resolveDefaultFilterChainSnapshot(
            ListenerXdsResource resource, ListenerFilterChainFactory factory,
            @Nullable ConfigSource parentConfigSource) {
        if (!resource.resource().hasDefaultFilterChain()) {
            return SnapshotStream.empty();
        }
        return factory.resolve(resource.resource().getDefaultFilterChain(), parentConfigSource)
                      .map(Optional::of);
    }
}
