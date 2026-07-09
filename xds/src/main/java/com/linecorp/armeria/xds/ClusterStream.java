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

import static com.linecorp.armeria.xds.XdsType.CLUSTER;

import java.util.List;
import java.util.Optional;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.client.endpoint.XdsLoadBalancer;
import com.linecorp.armeria.xds.client.endpoint.XdsLoadBalancerFactory;
import com.linecorp.armeria.xds.stream.RefCountedStream;
import com.linecorp.armeria.xds.stream.SnapshotStream;
import com.linecorp.armeria.xds.stream.Subscription;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.TransportSocketMatch;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.extensions.upstreams.http.v3.HttpProtocolOptions;

final class ClusterStream extends RefCountedStream<ClusterSnapshot> {

    private static final String HTTP_PROTOCOL_OPTIONS_KEY =
            "envoy.extensions.upstreams.http.v3.HttpProtocolOptions";

    @Nullable
    private final ClusterXdsResource clusterXdsResource;
    private final String resourceName;

    private final SubscriptionContext context;
    private final XdsLoadBalancerFactory lbFactory;

    ClusterStream(ClusterXdsResource clusterXdsResource, SubscriptionContext context) {
        this.clusterXdsResource = clusterXdsResource;
        this.context = context;
        resourceName = clusterXdsResource.name();
        lbFactory = XdsLoadBalancerFactory.of(context, resourceName);
    }

    ClusterStream(String resourceName, SubscriptionContext context) {
        this.context = context;
        this.resourceName = resourceName;
        clusterXdsResource = null;
        lbFactory = XdsLoadBalancerFactory.of(context, resourceName);
    }

    @Override
    protected void onStop() {
        lbFactory.close();
    }

    @Override
    protected Subscription onStart(SnapshotWatcher<ClusterSnapshot> watcher) {
        final ConfigSource configSource = context.configSourceMapper().cdsConfigSource();
        if (clusterXdsResource != null) {
            return resource2snapshot(clusterXdsResource, configSource).subscribe(watcher);
        }
        if (configSource == null) {
            watcher.onUpdate(null, new XdsResourceException(CLUSTER, resourceName, "config source not found"));
            return Subscription.noop();
        }
        return new ResourceNodeAdapter<ClusterXdsResource>(configSource, context, resourceName, CLUSTER)
                .switchMapEager(resource -> resource2snapshot(resource, configSource))
                .subscribe(watcher);
    }

    private SnapshotStream<ClusterSnapshot> resource2snapshot(ClusterXdsResource resource,
                                                              @Nullable ConfigSource configSource) {
        final TransportSocketStream transportSocket = new TransportSocketStream(
                context, configSource, resource.resource().getTransportSocket());
        final List<TransportSocketMatch> matches = resource.resource().getTransportSocketMatchesList();
        final TransportSocketMatchesStream socketMatchesStream =
                new TransportSocketMatchesStream(context, configSource, matches);
        final HttpProtocolOptions httpProtocolOptions = parseHttpProtocolOptions(resource.resource());
        return SnapshotStream.combineLatest(transportSocket, socketMatchesStream, LoadBalancerInput::new)
                             .switchMapEager(input -> clusterSnapshotStream(resource,
                                                                            httpProtocolOptions, input));
    }

    private SnapshotStream<ClusterSnapshot> clusterSnapshotStream(
            ClusterXdsResource resource, @Nullable HttpProtocolOptions httpProtocolOptions,
            LoadBalancerInput input) {
        final SnapshotStream<XdsLoadBalancer> lbStream;
        if (context.clusterManager().hasLocalCluster() &&
            !resourceName.equals(context.clusterManager().localClusterName())) {
            lbStream = new LocalClusterStream(context.clusterManager())
                    .switchMapEager(localCluster -> lbFactory.register(
                            resource,
                            input.transportSocket,
                            input.transportSocketMatches,
                            localCluster.map(ClusterSnapshot::loadBalancer).orElse(null)));
        } else {
            lbStream = lbFactory.register(resource, input.transportSocket,
                                          input.transportSocketMatches, null);
        }
        return lbStream.map(lb -> {
            final ClusterFilterFactory factory =
                    new ClusterFilterFactory(lb, httpProtocolOptions);
            return new ClusterSnapshot(resource, lb, input.transportSocket, input.transportSocketMatches,
                                       factory);
        });
    }

    @Nullable
    private HttpProtocolOptions parseHttpProtocolOptions(Cluster cluster) {
        final Any any = cluster.getTypedExtensionProtocolOptionsMap()
                               .get(HTTP_PROTOCOL_OPTIONS_KEY);
        if (any == null) {
            return null;
        }
        return context.extensionRegistry().unpack(any, HttpProtocolOptions.class);
    }

    private static class TransportSocketMatchesStream
            extends RefCountedStream<List<TransportSocketMatchSnapshot>> {

        private final SubscriptionContext context;
        @Nullable
        private final ConfigSource parentConfigSource;
        private final List<TransportSocketMatch> transportSocketMatchList;

        TransportSocketMatchesStream(SubscriptionContext context, @Nullable ConfigSource parentConfigSource,
                                     List<TransportSocketMatch> transportSocketMatchList) {
            this.context = context;
            this.parentConfigSource = parentConfigSource;
            this.transportSocketMatchList = transportSocketMatchList;
        }

        @Override
        protected Subscription onStart(SnapshotWatcher<List<TransportSocketMatchSnapshot>> watcher) {
            final ImmutableList.Builder<SnapshotStream<TransportSocketMatchSnapshot>> streamsBuilder =
                    ImmutableList.builder();
            for (TransportSocketMatch socketMatch : transportSocketMatchList) {
                streamsBuilder.add(new TransportSocketStream(context, parentConfigSource,
                                                             socketMatch.getTransportSocket())
                                           .map(socket -> new TransportSocketMatchSnapshot(socketMatch,
                                                                                           socket)));
            }
            return SnapshotStream.combineNLatest(streamsBuilder.build())
                                 .subscribe(watcher);
        }
    }

    private static class LocalClusterStream extends RefCountedStream<Optional<ClusterSnapshot>> {

        private final XdsClusterManager xdsClusterManager;

        LocalClusterStream(XdsClusterManager xdsClusterManager) {
            this.xdsClusterManager = xdsClusterManager;
        }

        @Override
        protected Subscription onStart(SnapshotWatcher<Optional<ClusterSnapshot>> watcher) {
            // local clusters are optional - late local clusters shouldn't block upstream cluster loading
            watcher.onUpdate(Optional.empty(), null);
            return xdsClusterManager.registerLocalWatcher(watcher);
        }
    }

    private static final class LoadBalancerInput {

        private final TransportSocketSnapshot transportSocket;
        private final List<TransportSocketMatchSnapshot> transportSocketMatches;

        LoadBalancerInput(TransportSocketSnapshot transportSocket,
                          List<TransportSocketMatchSnapshot> transportSocketMatches) {
            this.transportSocket = transportSocket;
            this.transportSocketMatches = transportSocketMatches;
        }
    }
}
