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
import static com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointGroupMockServerTest.newDeployment;
import static com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointGroupMockServerTest.newNode;
import static com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointGroupMockServerTest.newPod;
import static com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointGroupMockServerTest.newService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;

@EnableKubernetesMockClient(
        crud = true,
        kubernetesClientBuilderCustomizer = FaultInjectingKubernetesClientBuilderCustomizer.class)
class KubernetesEndpointGroupFaultToleranceTest {

    private KubernetesClient client;

    @Test
    void shouldReconnectOnWatcherException() throws InterruptedException {
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

        FaultInjectingKubernetesClientBuilderCustomizer.injectFault(true);
        // Add a new pod
        client.pods().resource(pods.get(2)).create();

        Thread.sleep(2000);

        assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(
                Endpoint.of("1.1.1.1", nodePort),
                Endpoint.of("2.2.2.2", nodePort)
        );

        FaultInjectingKubernetesClientBuilderCustomizer.injectFault(false);
        // Make sure the new pod is added when the fault is recovered.
        await().untilAsserted(() -> {
            final List<Endpoint> endpoints = endpointGroup.endpoints();
            assertThat(endpoints).containsExactlyInAnyOrder(
                    Endpoint.of("1.1.1.1", nodePort),
                    Endpoint.of("2.2.2.2", nodePort),
                    Endpoint.of("3.3.3.3", nodePort)
            );
        });
    }
}
