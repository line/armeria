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

package com.linecorp.armeria.xds.kubernetes;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;

import com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointGroup;
import com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointGroupBuilder;
import com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointMode;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.ClusterXdsResource;
import com.linecorp.armeria.xds.EndpointSnapshot;
import com.linecorp.armeria.xds.client.endpoint.ClusterTypeFactory;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.stream.SnapshotStream;

import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;

/**
 * A {@link ClusterTypeFactory} that resolves endpoints using a Kubernetes
 * {@link KubernetesEndpointGroup}.
 *
 * <p>The cluster's {@code typed_config} must be a
 * {@link com.linecorp.armeria.xds.kubernetes.KubernetesClusterConfig KubernetesClusterConfig}
 * protobuf message specifying the Kubernetes service to watch.
 *
 * <p>If the config includes a {@code credential} field referencing an SDS secret, the factory
 * uses it as a bearer token for Kubernetes API authentication. When the secret rotates, the
 * {@link KubernetesEndpointGroup} is recreated with the new token.
 *
 * <p>Example xDS cluster configuration:
 * <pre>{@code
 * cluster_type:
 *   name: armeria.cluster.kubernetes
 *   typed_config:
 *     "@type": type.googleapis.com/armeria.xds.kubernetes.KubernetesClusterConfig
 *     service_name: my-service
 *     namespace: production
 *     port_name: http
 *     mode: POD
 * }</pre>
 */
@UnstableApi
public final class KubernetesClusterTypeFactory implements ClusterTypeFactory {

    private static final String NAME = "armeria.cluster.kubernetes";
    private static final String TYPE_URL =
            "type.googleapis.com/armeria.xds.kubernetes.KubernetesClusterConfig";
    private static final List<String> TYPE_URLS = ImmutableList.of(TYPE_URL);

    /**
     * Returns a new factory with the default Kubernetes client configuration and the default
     * {@link KubernetesEndpointMapper}.
     */
    public static KubernetesClusterTypeFactory of() {
        return of(new ConfigBuilder().build(), KubernetesEndpointMapper.of());
    }

    /**
     * Returns a new factory with the specified {@link KubernetesEndpointMapper}.
     */
    public static KubernetesClusterTypeFactory of(KubernetesEndpointMapper mapper) {
        return of(new ConfigBuilder().build(), requireNonNull(mapper, "mapper"));
    }

    /**
     * Returns a new factory with the specified base {@link Config} and the default
     * {@link KubernetesEndpointMapper}. The base config provides TLS trust settings,
     * authentication defaults, and other Kubernetes client options that can be overridden
     * by the proto config fields ({@code api_server_url}, {@code credential}).
     */
    public static KubernetesClusterTypeFactory of(Config baseConfig) {
        return of(requireNonNull(baseConfig, "baseConfig"), KubernetesEndpointMapper.of());
    }

    /**
     * Returns a new factory with the specified base {@link Config} and
     * {@link KubernetesEndpointMapper}.
     */
    public static KubernetesClusterTypeFactory of(Config baseConfig, KubernetesEndpointMapper mapper) {
        return new KubernetesClusterTypeFactory(requireNonNull(baseConfig, "baseConfig"),
                                                requireNonNull(mapper, "mapper"));
    }

    private final Config baseConfig;
    private final KubernetesEndpointMapper mapper;

    private KubernetesClusterTypeFactory(Config baseConfig, KubernetesEndpointMapper mapper) {
        this.baseConfig = baseConfig;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> typeUrls() {
        return TYPE_URLS;
    }

    @Override
    public SnapshotStream<EndpointSnapshot> createEndpointStream(
            ClusterXdsResource clusterXdsResource, FactoryContext context) {
        final Any typedConfig = clusterXdsResource.resource().getClusterType().getTypedConfig();
        final KubernetesClusterConfig config = context.validator().unpack(
                typedConfig, KubernetesClusterConfig.class);
        final String clusterName = clusterXdsResource.name();

        if (config.hasCredential()) {
            return context.genericSecretStream(config.getCredential())
                          .switchMapEager(secretSnapshot -> {
                              final ConfigBuilder configBuilder = newConfigBuilder(config);
                              if (secretSnapshot.credential() != null) {
                                  configBuilder.withOauthToken(secretSnapshot.credential());
                              }
                              return createSnapshot(configBuilder, config, clusterName);
                          });
        }
        return createSnapshot(newConfigBuilder(config), config, clusterName);
    }

    private ConfigBuilder newConfigBuilder(KubernetesClusterConfig config) {
        final ConfigBuilder configBuilder = new ConfigBuilder(baseConfig);
        if (!config.getApiServerUrl().isEmpty()) {
            configBuilder.withMasterUrl(config.getApiServerUrl());
        }
        return configBuilder;
    }

    private SnapshotStream<EndpointSnapshot> createSnapshot(
            ConfigBuilder configBuilder, KubernetesClusterConfig config, String clusterName) {
        final KubernetesEndpointGroupBuilder builder =
                KubernetesEndpointGroup.builder(configBuilder.build())
                                       .serviceName(config.getServiceName());
        if (!config.getNamespace().isEmpty()) {
            builder.namespace(config.getNamespace());
        }
        if (!config.getPortName().isEmpty()) {
            builder.portName(config.getPortName());
        }
        if (config.getMode() ==
            com.linecorp.armeria.xds.kubernetes.KubernetesEndpointMode.NODE_PORT) {
            builder.mode(KubernetesEndpointMode.NODE_PORT);
        } else {
            builder.mode(KubernetesEndpointMode.POD);
        }
        return endpointGroupToSnapshot(builder.build(), clusterName);
    }

    private SnapshotStream<EndpointSnapshot> endpointGroupToSnapshot(
            KubernetesEndpointGroup endpointGroup, String clusterName) {
        return watcher -> {
            endpointGroup.addListener(endpoints -> {
                final ClusterLoadAssignment cla = mapper.map(clusterName, endpoints);
                watcher.onUpdate(EndpointSnapshot.of(cla), null);
            }, true);
            return endpointGroup::closeAsync;
        };
    }
}
