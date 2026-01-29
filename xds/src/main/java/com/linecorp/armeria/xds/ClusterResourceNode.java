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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.client.endpoint.UpdatableXdsLoadBalancer;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

final class ClusterResourceNode extends AbstractResourceNode<ClusterXdsResource, ClusterSnapshot> {

    @Nullable
    private EndpointSnapshotWatcher snapshotWatcher;
    private final UpdatableXdsLoadBalancer loadBalancer;

    ClusterResourceNode(@Nullable ConfigSource configSource,
                        String resourceName, SubscriptionContext context,
                        ResourceNodeType resourceNodeType, UpdatableXdsLoadBalancer loadBalancer) {
        super(context, configSource, CLUSTER, resourceName, resourceNodeType);
        this.loadBalancer = loadBalancer;
    }

    @Override
    public void doOnChanged(ClusterXdsResource resource) {
        final EndpointSnapshotWatcher previousWatcher = snapshotWatcher;
        if (previousWatcher != null) {
            previousWatcher.preClose();
        }
        snapshotWatcher = new EndpointSnapshotWatcher(resource, context(), this, configSource(), loadBalancer);
        if (previousWatcher != null) {
            previousWatcher.close();
        }
    }

    @Override
    void preClose() {
        final EndpointSnapshotWatcher snapshotWatcher = this.snapshotWatcher;
        if (snapshotWatcher != null) {
            snapshotWatcher.preClose();
        }
        super.preClose();
    }

    @Override
    public void close() {
        final EndpointSnapshotWatcher snapshotWatcher = this.snapshotWatcher;
        if (snapshotWatcher != null) {
            snapshotWatcher.close();
        }
        loadBalancer.close();
        super.close();
    }

    private static class EndpointSnapshotWatcher extends AbstractNodeSnapshotWatcher<EndpointSnapshot> {

        private final ClusterXdsResource resource;
        private final ClusterResourceNode parentNode;
        @Nullable
        private final EndpointResourceNode node;
        private final UpdatableXdsLoadBalancer loadBalancer;

        EndpointSnapshotWatcher(ClusterXdsResource resource, SubscriptionContext context,
                                ClusterResourceNode parentNode, @Nullable ConfigSource parentConfigSource,
                                UpdatableXdsLoadBalancer loadBalancer) {
            this.resource = resource;
            this.parentNode = parentNode;
            this.loadBalancer = loadBalancer;

            EndpointResourceNode node = null;
            final Cluster cluster = resource.resource();
            if (cluster.hasLoadAssignment()) {
                final ClusterLoadAssignment loadAssignment = cluster.getLoadAssignment();
                node = StaticResourceUtils.staticEndpoint(
                        context, loadAssignment.getClusterName(), this, loadAssignment,
                        resource.version(), resource.revision());
            } else if (cluster.hasEdsClusterConfig()) {
                final EdsClusterConfig edsClusterConfig = cluster.getEdsClusterConfig();
                final String serviceName = edsClusterConfig.getServiceName();
                final String clusterName = !isNullOrEmpty(serviceName) ? serviceName : cluster.getName();
                final ConfigSource configSource =
                        context.configSourceMapper()
                               .configSource(cluster.getEdsClusterConfig().getEdsConfig(),
                                             parentConfigSource, clusterName);
                node = new EndpointResourceNode(configSource, clusterName, context,
                                                this, ResourceNodeType.DYNAMIC);
                context.subscribe(node);
            } else {
                final ClusterSnapshot clusterSnapshot = new ClusterSnapshot(resource);
                parentNode.notifyOnChanged(clusterSnapshot);
            }
            this.node = node;
        }

        @Override
        protected void doSnapshotUpdated(EndpointSnapshot newSnapshot) {
            parentNode.notifyOnChanged(ClusterSnapshot.of(resource, newSnapshot, loadBalancer));
        }

        @Override
        protected void doOnError(Throwable t) {
            parentNode.notifyOnError(t);
        }

        @Override
        protected void doPreClose() {
            final EndpointResourceNode node = this.node;
            if (node != null) {
                node.preClose();
            }
        }

        @Override
        protected void doClose() {
            if (node != null) {
                node.close();
            }
        }
    }
}
