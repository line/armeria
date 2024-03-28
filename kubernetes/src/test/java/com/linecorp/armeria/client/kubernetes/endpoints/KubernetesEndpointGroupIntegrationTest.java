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
import static com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointGroupMockServerTest.newService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import io.fabric8.junit.jupiter.api.KubernetesTest;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;

@EnabledIf("com.linecorp.armeria.client.kubernetes.endpoints.KubernetesAvailableCondition#isRunning")
@KubernetesTest
class KubernetesEndpointGroupIntegrationTest {

    private KubernetesClient client;

    @Test
    void createEndpointsWithNodeIpAndPort() {
        // Use the node port allocated by the k8s cluster
        final Service service = newService(null);
        final Deployment deployment = newDeployment();
        client.apps().deployments().resource(deployment).create();
        final Service service0 = client.services().resource(service).create();

        try (KubernetesEndpointGroup endpointGroup =
                KubernetesEndpointGroup.builder(client)
                                       .serviceName(service0.getMetadata().getName())
                                       .build()) {
            await().untilAsserted(() -> {
                assertThat(endpointGroup.whenReady()).isDone();
                assertThat(endpointGroup.endpoints()).isNotEmpty();
                final Integer nodePort = service0.getSpec().getPorts().get(0).getNodePort();
                final List<String> nodeIps = client.nodes().list().getItems().stream().map(node -> {
                    return node.getStatus().getAddresses().stream()
                               .filter(address -> "InternalIP".equals(address.getType()))
                               .findFirst().get().getAddress();
                }).collect(toImmutableList());

                assertThat(endpointGroup.endpoints())
                        .allSatisfy(endpoint -> {
                            assertThat(endpoint.ipAddr()).isIn(nodeIps);
                            assertThat(endpoint.port()).isEqualTo(nodePort);
                        });
            });
        }
    }
}
