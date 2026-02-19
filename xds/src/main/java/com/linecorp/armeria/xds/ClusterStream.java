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

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.armeria.xds.XdsType.CLUSTER;

import java.util.List;
import java.util.Optional;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.client.endpoint.XdsLoadBalancer;
import com.linecorp.armeria.xds.client.endpoint.XdsLoadBalancerFactory;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.TransportSocketMatch;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

final class ClusterStream extends RefCountedStream<ClusterSnapshot> {

    @Nullable
    private final ClusterXdsResource clusterXdsResource;
    private final String resourceName;

    private final SubscriptionContext context;
    private final XdsLoadBalancerFactory loadBalancerFactory;
    private final LoadBalancerFactoryPool loadBalancerFactoryPool;

    ClusterStream(ClusterXdsResource clusterXdsResource, SubscriptionContext context,
                  LoadBalancerFactoryPool loadBalancerFactoryPool) {
        this.clusterXdsResource = clusterXdsResource;
        this.context = context;
        resourceName = clusterXdsResource.name();
        loadBalancerFactory = loadBalancerFactoryPool.register(resourceName);
        this.loadBalancerFactoryPool = loadBalancerFactoryPool;
    }

    ClusterStream(String resourceName, SubscriptionContext context,
                  LoadBalancerFactoryPool loadBalancerFactoryPool) {
        this.context = context;
        loadBalancerFactory = loadBalancerFactoryPool.register(resourceName);
        this.loadBalancerFactoryPool = loadBalancerFactoryPool;
        this.resourceName = resourceName;
        clusterXdsResource = null;
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
                .switchMap(resource -> resource2snapshot(resource, configSource))
                .subscribe(watcher);
    }

    @Override
    protected void onStop() {
        loadBalancerFactoryPool.unregister(resourceName);
    }

    private SnapshotStream<ClusterSnapshot> resource2snapshot(ClusterXdsResource resource,
                                                              @Nullable ConfigSource configSource) {
        final TransportSocketStream transportSocket = new TransportSocketStream(
                context, configSource, resource.resource().getTransportSocket());
        final List<TransportSocketMatch> matches = resource.resource().getTransportSocketMatchesList();
        final TransportSocketMatchesStream socketMatchesStream =
                new TransportSocketMatchesStream(context, configSource, matches);
        final SnapshotStream<Optional<EndpointSnapshot>> endpointSnapshotStream =
                new EndpointSnapshotNode(resource, context, configSource);
        final SnapshotStream<Optional<XdsLoadBalancer>> lbStream =
                SnapshotStream.combineLatest(endpointSnapshotStream, transportSocket, socketMatchesStream,
                                             LoadBalancerInput::new)
                              .switchMap(input -> {
                                  if (!input.endpointSnapshot.isPresent()) {
                                      return SnapshotStream.just(Optional.empty());
                                  }
                                  final EndpointSnapshot endpointSnapshot = input.endpointSnapshot.get();
                                  if (context.clusterManager().hasLocalCluster() &&
                                      !resourceName.equals(context.clusterManager().localClusterName())) {
                                      return new LocalClusterStream(context.clusterManager())
                                              .switchMap(localCluster -> {
                                                  return new LoadBalancerStream(
                                                          resource, endpointSnapshot,
                                                          input.transportSocket,
                                                          input.transportSocketMatches,
                                                          loadBalancerFactory, localCluster);
                                              }).map(Optional::of);
                                  } else {
                                      return new LoadBalancerStream(
                                              resource, endpointSnapshot,
                                              input.transportSocket,
                                              input.transportSocketMatches,
                                              loadBalancerFactory, Optional.empty())
                                              .map(Optional::of);
                                  }
                              });
        return SnapshotStream.combineLatest(
                lbStream, transportSocket, socketMatchesStream,
                (lb, socket, socketMatches) -> new ClusterSnapshot(resource, lb, socket, socketMatches));
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

        private final Optional<EndpointSnapshot> endpointSnapshot;
        private final TransportSocketSnapshot transportSocket;
        private final List<TransportSocketMatchSnapshot> transportSocketMatches;

        LoadBalancerInput(Optional<EndpointSnapshot> endpointSnapshot,
                          TransportSocketSnapshot transportSocket,
                          List<TransportSocketMatchSnapshot> transportSocketMatches) {
            this.endpointSnapshot = endpointSnapshot;
            this.transportSocket = transportSocket;
            this.transportSocketMatches = transportSocketMatches;
        }
    }

    private static class LoadBalancerStream extends RefCountedStream<XdsLoadBalancer> {

        private final ClusterXdsResource clusterXdsResource;
        private final EndpointSnapshot endpointSnapshot;
        private final TransportSocketSnapshot transportSocket;
        private final List<TransportSocketMatchSnapshot> transportSocketMatches;
        private final XdsLoadBalancerFactory loadBalancerFactory;
        @Nullable
        private final XdsLoadBalancer localLoadBalancer;

        LoadBalancerStream(ClusterXdsResource clusterXdsResource,
                           EndpointSnapshot endpointSnapshot,
                           TransportSocketSnapshot transportSocket,
                           List<TransportSocketMatchSnapshot> transportSocketMatches,
                           XdsLoadBalancerFactory loadBalancerFactory,
                           Optional<ClusterSnapshot> localSnapshot) {
            this.clusterXdsResource = clusterXdsResource;
            this.endpointSnapshot = endpointSnapshot;
            this.transportSocket = transportSocket;
            this.transportSocketMatches = transportSocketMatches;
            this.loadBalancerFactory = loadBalancerFactory;
            localLoadBalancer = localSnapshot.isPresent() ? localSnapshot.get().loadBalancer() : null;
        }

        @Override
        protected Subscription onStart(SnapshotWatcher<XdsLoadBalancer> watcher) {
            loadBalancerFactory.register(clusterXdsResource, endpointSnapshot,
                                         transportSocket, transportSocketMatches, watcher,
                                         localLoadBalancer);
            return Subscription.noop();
        }
    }

    private static class EndpointSnapshotNode extends RefCountedStream<Optional<EndpointSnapshot>> {

        private final ClusterXdsResource resource;
        private final SubscriptionContext context;
        @Nullable
        private final ConfigSource parentConfigSource;

        EndpointSnapshotNode(ClusterXdsResource resource, SubscriptionContext context,
                             @Nullable ConfigSource parentConfigSource) {
            this.resource = resource;
            this.context = context;
            this.parentConfigSource = parentConfigSource;
        }

        @Override
        protected Subscription onStart(SnapshotWatcher<Optional<EndpointSnapshot>> watcher) {
            final Cluster cluster = resource.resource();
            final SnapshotStream<Optional<EndpointSnapshot>> node;
            if (cluster.hasLoadAssignment()) {
                final ClusterLoadAssignment loadAssignment = cluster.getLoadAssignment();
                node = new EndpointStream(new EndpointXdsResource(loadAssignment), context).map(Optional::of);
            } else if (cluster.hasEdsClusterConfig()) {
                final EdsClusterConfig edsClusterConfig = cluster.getEdsClusterConfig();
                final String serviceName = edsClusterConfig.getServiceName();
                final String clusterName = !isNullOrEmpty(serviceName) ? serviceName : cluster.getName();
                final ConfigSource configSource =
                        context.configSourceMapper()
                               .configSource(cluster.getEdsClusterConfig().getEdsConfig(),
                                             parentConfigSource);
                if (configSource == null) {
                    final SnapshotStream<Optional<EndpointSnapshot>> stream =
                            SnapshotStream.error(new XdsResourceException(CLUSTER, clusterName,
                                                                          "config source not found"));
                    return stream.subscribe(watcher);
                }
                node = new EndpointStream(configSource, clusterName, context).map(Optional::of);
            } else {
                node = SnapshotStream.empty();
            }
            return node.subscribe(watcher);
        }
    }
}
