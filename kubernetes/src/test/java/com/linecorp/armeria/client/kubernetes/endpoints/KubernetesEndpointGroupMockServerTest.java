/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.client.kubernetes.endpoints;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeAddress;
import io.fabric8.kubernetes.api.model.NodeAddressBuilder;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.NodeStatus;
import io.fabric8.kubernetes.api.model.NodeStatusBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.ServiceSpec;
import io.fabric8.kubernetes.api.model.ServiceSpecBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpec;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpecBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;

@EnableKubernetesMockClient(crud = true)
class KubernetesEndpointGroupMockServerTest {

    private static KubernetesClient staticClient;

    private KubernetesClient client;

    @AfterAll
    static void afterAll() {
        // A workaround for the issue that the static client is leaked.
        // Remove once https://github.com/fabric8io/kubernetes-client/pull/5854 is released.
        staticClient.close();
    }

    @Test
    void createEndpointsWithNodeIpAndPort() throws InterruptedException {
        // Prepare Kubernetes resources
        final List<Node> nodes = ImmutableList.of(newNode("1.1.1.1"), newNode("2.2.2.2"), newNode("3.3.3.3"));
        final Deployment deployment = newDeployment();
        final int nodePort = 30000;
        final Service service = newService(nodePort);
        final List<Pod> pods = nodes.stream()
                                    .map(node -> node.getMetadata().getName())
                                    .map(nodeName -> newPod(deployment.getSpec().getTemplate(), nodeName))
                                    .collect(toImmutableList());

        // Create Kubernetes resources
        for (Node node : nodes) {
            client.nodes().resource(node).create();
        }
        client.pods().resource(pods.get(0)).create();
        client.pods().resource(pods.get(1)).create();
        client.apps().deployments().resource(deployment).create();
        client.services().resource(service).create();

        final KubernetesEndpointGroup endpointGroup = KubernetesEndpointGroup.of(client, "test",
                                                                                 "nginx-service");
        endpointGroup.whenReady().join();

        // Initial state
        await().untilAsserted(() -> {
            final List<Endpoint> endpoints = endpointGroup.endpoints();
            // Wait until all endpoints are ready
            assertThat(endpoints).containsExactlyInAnyOrder(
                    Endpoint.of("1.1.1.1", nodePort),
                    Endpoint.of("2.2.2.2", nodePort)
            );
        });

        // Add a new pod
        client.pods().resource(pods.get(2)).create();
        await().untilAsserted(() -> {
            final List<Endpoint> endpoints = endpointGroup.endpoints();
            assertThat(endpoints).containsExactlyInAnyOrder(
                    Endpoint.of("1.1.1.1", nodePort),
                    Endpoint.of("2.2.2.2", nodePort),
                    Endpoint.of("3.3.3.3", nodePort)
            );
        });

        // Remove a pod
        client.pods().resource(pods.get(0)).delete();
        await().untilAsserted(() -> {
            final List<Endpoint> endpoints = endpointGroup.endpoints();
            assertThat(endpoints).containsExactlyInAnyOrder(
                    Endpoint.of("2.2.2.2", nodePort),
                    Endpoint.of("3.3.3.3", nodePort)
            );
        });

        // Add a new node and a new pod
        final Node node4 = newNode("4.4.4.4");
        client.nodes().resource(node4).create();
        final Pod pod4 = newPod(deployment.getSpec().getTemplate(), node4.getMetadata().getName());
        client.pods().resource(pod4).create();
        await().untilAsserted(() -> {
            final List<Endpoint> endpoints = endpointGroup.endpoints();
            assertThat(endpoints).containsExactlyInAnyOrder(
                    Endpoint.of("2.2.2.2", nodePort),
                    Endpoint.of("3.3.3.3", nodePort),
                    Endpoint.of("4.4.4.4", nodePort)
            );
        });

        // Add an empty node
        final Node node5 = newNode("5.5.5.5");
        client.nodes().resource(node5).create();
        Thread.sleep(1000);
        // A node where no pod is running should not be added to the EndpointGroup
        await().untilAsserted(() -> {
            final List<Endpoint> endpoints = endpointGroup.endpoints();
            assertThat(endpoints).containsExactlyInAnyOrder(
                    Endpoint.of("2.2.2.2", nodePort),
                    Endpoint.of("3.3.3.3", nodePort),
                    Endpoint.of("4.4.4.4", nodePort)
            );
        });
    }

    @Test
    void shouldUsePortNameToGetNodePort() {
        final List<Node> nodes = ImmutableList.of(newNode("1.1.1.1"), newNode("2.2.2.2"), newNode("3.3.3.3"));
        final Deployment deployment = newDeployment();
        final int httpNodePort = 30000;
        final Service service = newService(httpNodePort);
        final List<Pod> pods = nodes.stream()
                                    .map(node -> node.getMetadata().getName())
                                    .map(nodeName -> newPod(deployment.getSpec().getTemplate(), nodeName))
                                    .collect(toImmutableList());

        final int httpsNodePort = httpNodePort + 1;
        final ServiceSpec serviceSpec =
                service.getSpec()
                       .toBuilder()
                       .withPorts(new ServicePortBuilder()
                                          .withPort(80)
                                          .withNodePort(httpNodePort)
                                          .withName("http")
                                          .build(),
                                  new ServicePortBuilder()
                                          .withPort(443)
                                          .withNodePort(httpsNodePort)
                                          .withName("https")
                                          .build())
                       .build();
        final Service service0 = service.toBuilder()
                                        .withSpec(serviceSpec)
                                        .build();
        for (Node node : nodes) {
            client.nodes().resource(node).create();
        }
        for (Pod pod : pods) {
            client.pods().resource(pod).create();
        }
        client.apps().deployments().resource(deployment).create();
        client.services().resource(service0).create();

        final String serviceName = service0.getMetadata().getName();
        try (KubernetesEndpointGroup endpointGroup = KubernetesEndpointGroup.builder(client, false)
                                                                            .serviceName(serviceName)
                                                                            .portName("https")
                                                                            .build()) {
            await().untilAsserted(() -> {
                assertThat(endpointGroup.whenReady()).isDone();
                assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(
                        Endpoint.of("1.1.1.1", httpsNodePort),
                        Endpoint.of("2.2.2.2", httpsNodePort),
                        Endpoint.of("3.3.3.3", httpsNodePort));
            });
        }

        try (KubernetesEndpointGroup endpointGroup = KubernetesEndpointGroup.builder(client, false)
                                                                            .serviceName(serviceName)
                                                                            .portName("http")
                                                                            .build()) {
            await().untilAsserted(() -> {
                assertThat(endpointGroup.whenReady()).isDone();
                assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(
                        Endpoint.of("1.1.1.1", httpNodePort),
                        Endpoint.of("2.2.2.2", httpNodePort),
                        Endpoint.of("3.3.3.3", httpNodePort));
            });
        }
    }

    @Test
    void createEndpointsWithNodeExternalIpAndPort() throws InterruptedException {
        final List<Node> nodes = ImmutableList.of(newNode("1.1.1.1"),
                                                  newNode("2.2.2.2", "ExternalIP"),
                                                  newNode("3.3.3.3", "ExternalIP"));
        final Deployment deployment = newDeployment();
        final int nodePort = 30000;
        final Service service = newService(nodePort);
        final List<Pod> pods = nodes.stream()
                                    .map(node -> node.getMetadata().getName())
                                    .map(nodeName -> newPod(deployment.getSpec().getTemplate(), nodeName))
                                    .collect(toImmutableList());

        // Create Kubernetes resources
        for (Node node : nodes) {
            client.nodes().resource(node).create();
        }
        client.pods().resource(pods.get(0)).create();
        client.pods().resource(pods.get(1)).create();
        client.pods().resource(pods.get(2)).create();
        client.apps().deployments().resource(deployment).create();
        client.services().resource(service).create();

        final KubernetesEndpointGroup endpointGroup =
                KubernetesEndpointGroup.builder(client)
                                       .namespace("test")
                                       .serviceName("nginx-service")
                                       .nodeAddressFilter(
                                               nodeAddress -> "ExternalIP".equals(nodeAddress.getType()))
                                       .build();
        endpointGroup.whenReady().join();

        await().untilAsserted(() -> {
            final List<Endpoint> endpoints = endpointGroup.endpoints();
            assertThat(endpoints).containsExactlyInAnyOrder(
                    Endpoint.of("2.2.2.2", nodePort),
                    Endpoint.of("3.3.3.3", nodePort)
            );
        });
        endpointGroup.close();
    }

    private static Node newNode(String ip, String type) {
        final NodeAddress nodeAddress = new NodeAddressBuilder()
                .withType(type)
                .withAddress(ip)
                .build();
        final NodeStatus nodeStatus = new NodeStatusBuilder()
                .withAddresses(nodeAddress)
                .build();
        final ObjectMeta metadata = new ObjectMetaBuilder()
                .withName("node-" + ip)
                .build();
        return new NodeBuilder()
                .withMetadata(metadata)
                .withStatus(nodeStatus)
                .build();
    }

    private static Node newNode(String ip) {
        return newNode(ip, "InternalIP");
    }

    static Service newService(@Nullable Integer nodePort) {
        final ObjectMeta metadata = new ObjectMetaBuilder()
                .withName("nginx-service")
                .build();
        final ServicePort servicePort = new ServicePortBuilder()
                .withPort(80)
                .withNodePort(nodePort)
                .build();
        final ServiceSpec serviceSpec = new ServiceSpecBuilder()
                .withPorts(servicePort)
                .withSelector(ImmutableMap.of("app", "nginx"))
                .withType("NodePort")
                .build();
        return new ServiceBuilder()
                .withMetadata(metadata)
                .withSpec(serviceSpec)
                .build();
    }

    static Deployment newDeployment() {
        final ObjectMeta metadata = new ObjectMetaBuilder()
                .withName("nginx-deployment")
                .build();
        final LabelSelector selector = new LabelSelectorBuilder()
                .withMatchLabels(ImmutableMap.of("app", "nginx"))
                .build();
        final DeploymentSpec deploymentSpec = new DeploymentSpecBuilder()
                .withReplicas(4)
                .withSelector(selector)
                .withTemplate(newPodTemplate())
                .build();
        return new DeploymentBuilder()
                .withMetadata(metadata)
                .withSpec(deploymentSpec)
                .build();
    }

    private static PodTemplateSpec newPodTemplate() {
        final ObjectMeta metadata = new ObjectMetaBuilder()
                .withLabels(ImmutableMap.of("app", "nginx"))
                .build();
        final Container container = new ContainerBuilder()
                .withName("nginx")
                .withImage("nginx:1.14.2")
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

    private static Pod newPod(PodTemplateSpec template, String newNodeName) {
        final PodSpec spec = template.getSpec()
                                     .toBuilder()
                                     .withNodeName(newNodeName)
                                     .build();
        final ObjectMeta metadata = template.getMetadata()
                                            .toBuilder()
                                            .withName("nginx-pod-" + newNodeName)
                                            .build();
        return new PodBuilder()
                .withMetadata(metadata)
                .withSpec(spec)
                .build();
    }
}
