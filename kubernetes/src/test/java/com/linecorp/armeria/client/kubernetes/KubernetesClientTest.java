/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.client.kubernetes;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.ServiceResource;

class KubernetesClientTest {

    final KubernetesClient client = new KubernetesClientBuilder().build();

    private static final Logger logger = LoggerFactory.getLogger(KubernetesClientTest.class);

    // TODO(ikhoon): Add tests
    @Test
    void getServices() {
        final ServiceResource<Service> service = client.services().inNamespace("default").withName(
                "mongo-express-service");
        System.out.println(service);
        final Integer nodePort = service.get().getSpec().getPorts().get(0).getNodePort();
        final Map<String, String> selector = service.get().getSpec().getSelector();
        client.nodes().list().getItems().stream()
              .filter(node -> node.getStatus().getAddresses().stream()
                                  .anyMatch(address -> address.getType().equals("InternalIP"))).forEach(
                      node -> {
                          System.out.println(node.getMetadata().getName());
                      });
        final List<String> nodeNames = getNodeNames(selector);
        logger.info("Node names: {}", nodeNames);
        final List<String> internalIps = getInternalIps(nodeNames);
        for (String internalIp : internalIps) {
            logger.info("endpoint : " + internalIp + ":" + nodePort);
        }
    }

    @Test
    void getInternalIps() {
        final List<String> nodeNames = getNodeNames(ImmutableMap.of("app", "mongo-express-pod"));
        final List<String> internalIps = getInternalIps(nodeNames);
        System.out.println(internalIps);
    }

    @Timeout(Long.MAX_VALUE)
    @Test
    void watchPods() {
        CompletableFuture<Void> closeFuture = new CompletableFuture<>();
        final Watcher<Pod> watcher = new Watcher<Pod>() {

            @Override
            public void eventReceived(Action action, Pod resource) {
                logger.info("Event received: action={}, node={}, resource={}", action, resource.getSpec().getNodeName(), resource.getMetadata().getName());
            }

            @Override
            public void onClose(WatcherException cause) {
                logger.info("Watcher closed: cause={}", cause);
                closeFuture.complete(null);
            }
        };
        final Watch watch = client.pods()
                                  .inNamespace("default")
                                  .withLabels(ImmutableMap.of("app", "mongo-express-pod"))
                                  .watch(watcher);

        closeFuture.join();
    }

    private List<String> getInternalIps(List<String> nodeNames) {
        return client.nodes()
                     .list().getItems().stream()
                     .filter(node -> nodeNames.contains(node.getMetadata().getName()))
                     .map(node -> {
                         return node.getStatus().getAddresses().stream()
                                    .filter(address -> address.getType().equals("InternalIP"))
                                    .map(address -> address.getAddress())
                                    .findFirst().orElse(null);
                     })
                     .collect(toImmutableList());
    }

    private List<String> getNodeNames(Map<String, String> selector) {
        final PodList pods = client.pods().inNamespace("default")
                                   .withLabels(selector)
                                   .list();
        return pods.getItems().stream()
                   .map(pod -> pod.getSpec().getNodeName())
                   .collect(toImmutableList());
    }
}
