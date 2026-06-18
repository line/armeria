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

package com.linecorp.armeria.xds;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.armeria.xds.XdsType.CLUSTER;

import com.linecorp.armeria.xds.client.endpoint.ClusterTypeFactory;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.stream.SnapshotStream;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;

/**
 * A {@link ClusterTypeFactory} for EDS cluster types that subscribes to endpoint data
 * via the xDS control plane.
 *
 * <p>This factory lives in the {@code com.linecorp.armeria.xds} package because it needs
 * access to package-private classes ({@link EndpointStream}, {@link EndpointXdsResource},
 * {@link ConfigSourceMapper}).
 */
final class EdsClusterTypeFactory implements ClusterTypeFactory {

    static final String NAME = "armeria.cluster.eds";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public SnapshotStream<EndpointSnapshot> createEndpointStream(
            ClusterXdsResource clusterXdsResource, FactoryContext context) {
        final SubscriptionContext subCtx = (SubscriptionContext) context;
        final Cluster cluster = clusterXdsResource.resource();
        if (!cluster.hasEdsClusterConfig()) {
            return SnapshotStream.error(new XdsResourceException(CLUSTER, cluster.getName(),
                                                                 "eds_cluster_config is not specified"));
        }
        final EdsClusterConfig edsClusterConfig = cluster.getEdsClusterConfig();
        final String serviceName = edsClusterConfig.getServiceName();
        final String clusterName = !isNullOrEmpty(serviceName) ? serviceName : cluster.getName();
        final ConfigSource parentConfigSource = subCtx.configSourceMapper().cdsConfigSource();
        final ConfigSource configSource =
                subCtx.configSourceMapper().configSource(edsClusterConfig.getEdsConfig(),
                                                         parentConfigSource);
        if (configSource == null) {
            return SnapshotStream.error(new XdsResourceException(CLUSTER, clusterName,
                                                                 "config source not found"));
        }
        return new EndpointStream(configSource, clusterName, subCtx);
    }
}
