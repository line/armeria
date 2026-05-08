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
package com.linecorp.armeria.it.istio.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.DockerImageName;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeCondition;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

final class K8sClusterHelper {

    private static final Logger k3sLogger = LoggerFactory.getLogger("com.linecorp.armeria.k3s.logger");

    static final Duration DEFAULT_READY_TIMEOUT = Duration.ofMinutes(3);
    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);

    private static final String K3S_IMAGE = "rancher/k3s:v1.30.0-k3s1";

    private K8sClusterHelper() {}

    static K3sContainer startK3s() {
        final K3sContainer k3s = new K3sContainer(DockerImageName.parse(K3S_IMAGE));
        // uncomment for debugging
        // .withLogConsumer(new Slf4jLogConsumer(k3sLogger).withPrefix("k3s"));
        k3s.start();
        return k3s;
    }

    static K3sContainer startK3sAndWaitReady() throws Exception {
        return startK3sAndWaitReady(DEFAULT_READY_TIMEOUT, DEFAULT_POLL_INTERVAL);
    }

    static K3sContainer startK3sAndWaitReady(Duration timeout, Duration pollInterval) throws Exception {
        final K3sContainer k3s = startK3s();
        try {
            try (KubernetesClient client = createClient(k3s.getKubeConfigYaml())) {
                if (!waitForReadyNode(client, timeout, pollInterval)) {
                    k3sLogger.warn("Failed to start K3s cluster within timeout: {}", k3s.getLogs());
                    throw new IllegalStateException("Timed out waiting for K3s cluster to be Ready.");
                }
            }
            IstioTestImage.loadIntoK3s(k3s);
        } catch (Exception e) {
            k3s.stop();
            throw e;
        }
        return k3s;
    }

    static KubernetesClient createClient(Path kubeconfigPath) throws IOException {
        return createClient(Files.readString(kubeconfigPath));
    }

    private static KubernetesClient createClient(String kubeconfig) {
        final Config config = Config.fromKubeconfig(kubeconfig);
        config.setConnectionTimeout(3_000);
        config.setRequestTimeout(3_000);
        config.setRequestRetryBackoffLimit(0);
        return new KubernetesClientBuilder().withConfig(config).build();
    }

    static boolean poll(Duration timeout, Duration interval, BooleanSupplier condition) {
        final long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (condition.getAsBoolean()) {
                return true;
            }
            if (System.nanoTime() >= deadlineNanos) {
                return false;
            }
            sleep(interval);
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
        }
    }

    private static boolean waitForReadyNode(KubernetesClient client, Duration timeout, Duration pollInterval) {
        return poll(timeout, pollInterval, () -> hasReadyNodeSafely(client));
    }

    private static boolean hasReadyNodeSafely(KubernetesClient client) {
        try {
            return hasReadyNode(client);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean hasReadyNode(KubernetesClient client) {
        final List<Node> nodes = client.nodes().list().getItems();
        return !nodes.isEmpty() && nodes.stream().allMatch(K8sClusterHelper::isReady);
    }

    private static boolean isReady(Node node) {
        if (node.getStatus() == null || node.getStatus().getConditions() == null) {
            return false;
        }
        return node.getStatus().getConditions().stream()
                   .anyMatch(K8sClusterHelper::isReadyCondition);
    }

    private static boolean isReadyCondition(NodeCondition condition) {
        return "Ready".equals(condition.getType()) && "True".equals(condition.getStatus());
    }

    private static void sleep(Duration interval) {
        final long millis = interval.toMillis();
        try {
            Thread.sleep(millis > 0 ? millis : 1L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
