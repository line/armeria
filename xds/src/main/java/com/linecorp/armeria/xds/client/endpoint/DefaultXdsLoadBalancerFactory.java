/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import java.util.List;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ClusterXdsResource;
import com.linecorp.armeria.xds.EndpointSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.TransportSocketMatchSnapshot;
import com.linecorp.armeria.xds.TransportSocketSnapshot;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.stream.RefCountedStream;
import com.linecorp.armeria.xds.stream.SnapshotStream;
import com.linecorp.armeria.xds.stream.Subscription;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.netty.util.concurrent.EventExecutor;

final class DefaultXdsLoadBalancerFactory implements XdsLoadBalancerFactory {

    private final AttributesPool attributesPool = new AttributesPool();
    private final FactoryContext factoryContext;
    private final EventExecutor eventLoop;
    private final Locality locality;
    private final XdsLoadBalancerLifecycleObserver observer;
    private final LbSelectionRecorder selectionRecorder;
    private final SubsetSelectionRecorder subsetRecorder;

    DefaultXdsLoadBalancerFactory(FactoryContext context, String clusterName) {
        factoryContext = context;
        eventLoop = context.eventLoop();
        locality = context.bootstrap().getNode().getLocality();
        observer = new DefaultXdsLoadBalancerLifecycleObserver(
                context.meterIdPrefix(), context.meterRegistry(), clusterName);
        selectionRecorder = new LbSelectionRecorder(context.meterRegistry(), context.meterIdPrefix(),
                                                    clusterName);
        subsetRecorder = new SubsetSelectionRecorder(context.meterRegistry(),
                                                     context.meterIdPrefix(), clusterName);
    }

    @Override
    public SnapshotStream<XdsLoadBalancer> register(
            ClusterXdsResource clusterXdsResource,
            TransportSocketSnapshot transportSocket,
            List<TransportSocketMatchSnapshot> transportSocketMatches,
            @Nullable XdsLoadBalancer localLoadBalancer) {
        observer.resourceUpdated(clusterXdsResource);
        final ClusterTypeFactory clusterTypeFactory = resolveClusterTypeFactory(clusterXdsResource);
        return new LoadBalancerStream(clusterXdsResource,
                                      transportSocket, transportSocketMatches,
                                      localLoadBalancer, clusterTypeFactory);
    }

    private ClusterTypeFactory resolveClusterTypeFactory(ClusterXdsResource clusterXdsResource) {
        final Cluster cluster = clusterXdsResource.resource();
        if (cluster.hasClusterType()) {
            final Cluster.CustomClusterType customType = cluster.getClusterType();
            final ClusterTypeFactory factory = factoryContext.extensionRegistry().query(
                    customType.getTypedConfig(), customType.getName(), ClusterTypeFactory.class);
            if (factory != null) {
                return factory;
            }
            throw new UnsupportedOperationException(
                    "Cluster (" + cluster.getName() + ") has unsupported custom cluster type: (" +
                    customType.getName() + ").");
        }
        final String name = builtInClusterTypeName(cluster);
        final ClusterTypeFactory factory = factoryContext.extensionRegistry()
                                                         .queryByName(name, ClusterTypeFactory.class);
        assert factory != null : "No ClusterTypeFactory for " + cluster.getType();
        return factory;
    }

    private static String builtInClusterTypeName(Cluster cluster) {
        switch (cluster.getType()) {
            case STATIC:
                return StaticClusterTypeFactory.extensionName();
            case EDS:
                // EdsClusterTypeFactory is package-private in com.linecorp.armeria.xds
                return "armeria.cluster.eds";
            case STRICT_DNS:
                return StrictDnsClusterTypeFactory.extensionName();
            case ORIGINAL_DST:
                return "armeria.cluster.original_dst";
            default:
                throw new UnsupportedOperationException(
                        "Cluster (" + cluster.getName() + ") is attempting to use an " +
                        "unsupported cluster type: (" + cluster.getType() + "). " +
                        "Only (STATIC, STRICT_DNS, EDS) are supported.");
        }
    }

    private final class LoadBalancerStream extends RefCountedStream<XdsLoadBalancer> {

        private final ClusterXdsResource clusterXdsResource;
        private final TransportSocketSnapshot transportSocket;
        private final List<TransportSocketMatchSnapshot> transportSocketMatches;
        @Nullable
        private final XdsLoadBalancer localLoadBalancer;
        private final ClusterTypeFactory clusterTypeFactory;

        LoadBalancerStream(ClusterXdsResource clusterXdsResource,
                           TransportSocketSnapshot transportSocket,
                           List<TransportSocketMatchSnapshot> transportSocketMatches,
                           @Nullable XdsLoadBalancer localLoadBalancer,
                           ClusterTypeFactory clusterTypeFactory) {
            this.clusterXdsResource = clusterXdsResource;
            this.transportSocket = transportSocket;
            this.transportSocketMatches = transportSocketMatches;
            this.localLoadBalancer = localLoadBalancer;
            this.clusterTypeFactory = clusterTypeFactory;
        }

        @Override
        protected Subscription onStart(SnapshotWatcher<XdsLoadBalancer> watcher) {
            return clusterTypeFactory
                    .createEndpointStream(clusterXdsResource, factoryContext)
                    .rescheduleEventsOn(eventLoop)
                    .switchMapEager(snapshot -> {
                        final List<Endpoint> endpoints =
                                XdsEndpointUtil.convertLoadAssignment(snapshot.xdsResource().resource(),
                                                                      transportSocket, transportSocketMatches);
                        final EndpointGroup staticGroup = EndpointGroup.of(endpoints);
                        final EndpointGroup healthChecked = XdsEndpointUtil.maybeHealthChecked(
                                staticGroup, clusterXdsResource.resource());
                        return XdsEndpointUtil.endpointGroupToStream(healthChecked)
                                              .map(resolved -> new ResolvedEndpoints(
                                                      snapshot, resolved));
                    })
                    .subscribe((resolved, error) -> {
                        if (error != null) {
                            watcher.onUpdate(null, error);
                            return;
                        }
                        assert resolved != null;
                        updateEndpoints(clusterXdsResource, resolved.snapshot,
                                        resolved.endpoints, localLoadBalancer, watcher);
                    });
        }

        private void updateEndpoints(ClusterXdsResource clusterXdsResource,
                                     EndpointSnapshot endpointSnapshot,
                                     List<Endpoint> endpoints,
                                     @Nullable XdsLoadBalancer localLoadBalancer,
                                     SnapshotWatcher<XdsLoadBalancer> watcher) {
            endpoints = attributesPool.cacheAttributesAndDelegate(endpoints);
            observer.endpointsUpdated(clusterXdsResource, endpoints);
            try {
                final PrioritySet prioritySet =
                        new PriorityStateManager(clusterXdsResource, endpointSnapshot, endpoints).build();

                XdsLoadBalancer loadBalancer =
                        new DefaultLoadBalancer(prioritySet, locality, localLoadBalancer,
                                                selectionRecorder);
                if (clusterXdsResource.resource().hasLbSubsetConfig()) {
                    loadBalancer = new SubsetLoadBalancer(prioritySet, loadBalancer, locality,
                                                          localLoadBalancer, selectionRecorder,
                                                          subsetRecorder);
                }
                observer.stateUpdated(clusterXdsResource, loadBalancer);
                watcher.onUpdate(loadBalancer, null);
            } catch (Exception e) {
                observer.stateRejected(clusterXdsResource, endpoints, e);
                watcher.onUpdate(null, e);
            }
        }
    }

    private static final class ResolvedEndpoints {
        final EndpointSnapshot snapshot;
        final List<Endpoint> endpoints;

        ResolvedEndpoints(EndpointSnapshot snapshot, List<Endpoint> endpoints) {
            this.snapshot = snapshot;
            this.endpoints = endpoints;
        }
    }

    @Override
    public void close() {
        observer.close();
    }
}
