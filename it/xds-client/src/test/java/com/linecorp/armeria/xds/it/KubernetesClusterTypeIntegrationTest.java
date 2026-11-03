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

package com.linecorp.armeria.xds.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;
import com.linecorp.armeria.xds.kubernetes.KubernetesClusterTypeFactory;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.ServiceSpecBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpecBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;

@EnableKubernetesMockClient(crud = true)
class KubernetesClusterTypeIntegrationTest {

    private static final Map<String, String> LABELS = ImmutableMap.of("app", "test-app");

    KubernetesClient client;

    @RegisterExtension
    static final ServerExtension backendServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/hello", (ctx, req) -> HttpResponse.of("world"));
        }
    };

    @BeforeEach
    void setUp() {
        createK8sResources();
    }

    @Test
    void basicEndpointDiscovery() {
        final Bootstrap bootstrap = bootstrapYaml(client.getMasterUrl().toString());
        final KubernetesClusterTypeFactory factory = KubernetesClusterTypeFactory.of(
                client.getConfiguration(),
                (clusterName, endpoints) -> backendCla(clusterName));

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .extensionFactories(factory)
                                                     .build();
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("listener1", xdsBootstrap)) {
            final BlockingWebClient webClient = WebClient.of(preprocessor).blocking();
            assertThat(webClient.get("/hello").contentUtf8()).isEqualTo("world");
        }
    }

    private void createK8sResources() {
        final Deployment deployment = newDeployment();
        final Service service = newService();
        client.apps().deployments().resource(deployment).create();
        client.services().resource(service).create();

        final PodTemplateSpec template = deployment.getSpec().getTemplate();
        client.pods().resource(newPodWithIp(template, "pod-0", "10.0.0.1")).create();
    }

    private static Deployment newDeployment() {
        final ObjectMeta metadata = new ObjectMetaBuilder()
                .withName("test-deployment")
                .build();
        return new DeploymentBuilder()
                .withMetadata(metadata)
                .withSpec(new DeploymentSpecBuilder()
                                  .withSelector(new LabelSelectorBuilder().withMatchLabels(LABELS).build())
                                  .withTemplate(newPodTemplate())
                                  .build())
                .build();
    }

    private static PodTemplateSpec newPodTemplate() {
        final ObjectMeta metadata = new ObjectMetaBuilder()
                .withLabels(LABELS)
                .build();
        final Container container = new ContainerBuilder()
                .withName("app")
                .withImage("app:latest")
                .withPorts(new ContainerPortBuilder()
                                   .withContainerPort(8080)
                                   .build())
                .build();
        final PodSpec spec = new PodSpecBuilder()
                .withContainers(container)
                .build();
        return new PodTemplateSpecBuilder()
                .withMetadata(metadata)
                .withSpec(spec)
                .build();
    }

    private static Pod newPodWithIp(PodTemplateSpec template, String podName, String podIp) {
        final PodSpec spec = template.getSpec()
                                     .toBuilder()
                                     .withNodeName("dummy-node")
                                     .build();
        final ObjectMeta metadata = template.getMetadata()
                                            .toBuilder()
                                            .withName(podName)
                                            .build();
        return new PodBuilder()
                .withMetadata(metadata)
                .withSpec(spec)
                .withStatus(new PodStatusBuilder().withPodIP(podIp).build())
                .build();
    }

    private static Service newService() {
        final ObjectMeta metadata = new ObjectMetaBuilder().withName("test-service")
                                                           .build();
        return new ServiceBuilder()
                .withMetadata(metadata)
                .withSpec(new ServiceSpecBuilder().withPorts(new ServicePortBuilder().withPort(8080).build())
                                                  .withSelector(LABELS)
                                                  .withType("ClusterIP")
                                                  .build())
                .build();
    }

    private static ClusterLoadAssignment backendCla(String clusterName) {
        //language=YAML
        final String yaml = """
                cluster_name: %s
                endpoints:
                - lb_endpoints:
                  - endpoint:
                      address:
                        socket_address:
                          address: 127.0.0.1
                          port_value: %s
                """.formatted(clusterName, backendServer.httpPort());
        return XdsResourceReader.fromYaml(yaml, ClusterLoadAssignment.class);
    }

    private static Bootstrap bootstrapYaml(String apiServerUrl) {
        //language=YAML
        final String yaml = """
                static_resources:
                  listeners:
                  - name: listener1
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network\
                .http_connection_manager.v3.HttpConnectionManager
                        stat_prefix: http
                        route_config:
                          name: route1
                          virtual_hosts:
                          - name: local_service1
                            domains: [ "*" ]
                            routes:
                            - match:
                                prefix: /
                              route:
                                cluster: cluster1
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                  clusters:
                  - name: cluster1
                    cluster_type:
                      name: armeria.cluster.kubernetes
                      typed_config:
                        "@type": type.googleapis.com/armeria.xds.kubernetes.KubernetesClusterConfig
                        service_name: test-service
                        namespace: test
                        mode: POD
                        api_server_url: "%s"
                """.formatted(apiServerUrl);
        return XdsResourceReader.fromYaml(yaml, Bootstrap.class);
    }
}
