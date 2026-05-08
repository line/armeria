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

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.server.ServerConfigurator;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * JUnit 5 extension that deploys a server workload into the K3s cluster using a
 * {@link ServerConfigurator} class to configure the server. The deployment runs
 * {@link IstioPodEntryPoint} with {@code --server-factory} and {@code --port} arguments,
 * which instantiates and invokes the given configurator class inside the pod.
 * The configurator class must have an accessible no-arg constructor.
 *
 * <p>Requires {@link IstioClusterExtension} to be registered on the same test class.
 *
 * <p>Example usage:
 * <pre>{@code
 * @Order(1)
 * @RegisterExtension
 * static IstioClusterExtension cluster = new IstioClusterExtension();
 *
 * @Order(2)
 * @RegisterExtension
 * static IstioServerExtension echo = new IstioServerExtension("echo", 8080, EchoConfigurator.class);
 * }</pre>
 */
public final class IstioServerExtension extends HostOnlyExtension {

    private static final Logger logger = LoggerFactory.getLogger(IstioServerExtension.class);

    private static final String NAMESPACE = "default";
    private static final Duration READY_TIMEOUT = Duration.ofMinutes(3);

    private final String serviceName;
    private final int port;
    private final Class<? extends ServerConfigurator> configuratorClass;

    public IstioServerExtension(String serviceName, int port,
                                Class<? extends ServerConfigurator> configuratorClass) {
        this.serviceName = requireNonNull(serviceName, "serviceName");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port: " + port + " (expected: 1-65535)");
        }
        this.port = port;
        this.configuratorClass = requireNonNull(configuratorClass, "configuratorClass");
    }

    /**
     * Returns the Kubernetes service name.
     */
    public String serviceName() {
        return serviceName;
    }

    /**
     * Returns the port the server listens on.
     */
    public int port() {
        return port;
    }

    @Override
    void setUp(ExtensionContext context) throws Exception {
        final ExtensionContext.Store store = context.getStore(IstioClusterExtension.NAMESPACE);

        final KubernetesClient client = store.get(IstioClusterExtension.K8S_CLIENT_KEY,
                                                  KubernetesClient.class);
        if (client == null) {
            throw new IllegalStateException(
                    "KubernetesClient not found in store. " +
                    "Ensure IstioClusterExtension is registered on this test class.");
        }

        createDeployment(client);
        createService(client);
        waitForReady(client);
    }

    @Override
    void tearDown(ExtensionContext context) throws Exception {
        final ExtensionContext.Store store = context.getStore(IstioClusterExtension.NAMESPACE);
        final KubernetesClient client = store.get(IstioClusterExtension.K8S_CLIENT_KEY,
                                                  KubernetesClient.class);
        if (client == null) {
            return;
        }
        collectServerPodLogs(client);
        client.apps().deployments().inNamespace(NAMESPACE).withName(serviceName).delete();
        client.services().inNamespace(NAMESPACE).withName(serviceName).delete();
        logger.info("Deleted deployment and service '{}'", serviceName);
    }

    private void collectServerPodLogs(KubernetesClient client) {
        try {
            client.pods().inNamespace(NAMESPACE)
                  .withLabel("app", serviceName)
                  .list().getItems()
                  .forEach(pod -> {
                      final String podName = pod.getMetadata().getName();
                      pod.getSpec().getContainers().forEach(c -> {
                          try {
                              final String logs = client.pods().inNamespace(NAMESPACE)
                                                        .withName(podName)
                                                        .inContainer(c.getName()).getLog();
                              logger.info("=== Pod '{}' container '{}' logs ===\n{}",
                                          podName, c.getName(), logs);
                          } catch (Exception e) {
                              logger.debug("Failed to get logs for pod '{}' container '{}'",
                                           podName, c.getName());
                          }
                      });
                  });
        } catch (Exception e) {
            logger.warn("Failed to collect server pod logs for '{}'", serviceName, e);
        }
    }

    private void createDeployment(KubernetesClient client) {
        final Map<String, String> labels = Map.of("app", serviceName);
        client.apps().deployments().inNamespace(NAMESPACE)
              .resource(new DeploymentBuilder()
                                .withNewMetadata()
                                .withName(serviceName)
                                .withNamespace(NAMESPACE)
                                .endMetadata()
                                .withNewSpec()
                                .withReplicas(1)
                                .withNewSelector()
                                .withMatchLabels(labels)
                                .endSelector()
                                .withNewTemplate()
                                .withNewMetadata()
                                .withLabels(labels)
                                .withAnnotations(Map.of("sidecar.istio.io/inject", "true"))
                                .endMetadata()
                                .withNewSpec()
                                .addNewContainer()
                                .withName("server")
                                .withImage(IstioTestImage.IMAGE_NAME)
                                .withImagePullPolicy("Never")
                                .withArgs("--server-factory", configuratorClass.getName(),
                                          "--port", String.valueOf(port))
                                .addNewEnv()
                                    .withName("JAVA_TOOL_OPTIONS")
                                    .withValue(IstioEnv.podJvmArgs())
                                .endEnv()
                                .endContainer()
                                .endSpec()
                                .endTemplate()
                                .endSpec()
                                .build())
              .create();
        logger.info("Created deployment '{}' with server-factory '{}'",
                    serviceName, configuratorClass.getName());
    }

    private void createService(KubernetesClient client) {
        client.services().inNamespace(NAMESPACE)
              .resource(new ServiceBuilder()
                                .withNewMetadata()
                                .withName(serviceName)
                                .withNamespace(NAMESPACE)
                                .endMetadata()
                                .withNewSpec()
                                .withSelector(Map.of("app", serviceName))
                                .addNewPort()
                                .withPort(port)
                                .withTargetPort(new IntOrString(port))
                                .endPort()
                                .endSpec()
                                .build())
              .create();
        logger.info("Created service '{}' on port {}", serviceName, port);
    }

    private void waitForReady(KubernetesClient client) {
        logger.info("Waiting for deployment '{}' to be ready...", serviceName);
        final boolean ready = K8sClusterHelper.poll(
                READY_TIMEOUT, K8sClusterHelper.DEFAULT_POLL_INTERVAL, () -> {
            final List<Pod> pods = client.pods().inNamespace(NAMESPACE)
                                         .withLabel("app", serviceName)
                                         .list()
                                         .getItems();
            return pods.stream().anyMatch(pod -> {
                if (pod.getStatus() == null || !"Running".equals(pod.getStatus().getPhase())) {
                    return false;
                }
                final var statuses = pod.getStatus().getContainerStatuses();
                if (statuses == null) {
                    return false;
                }
                final boolean serverReady = statuses.stream()
                        .filter(cs -> "server".equals(cs.getName()))
                        .anyMatch(cs -> Boolean.TRUE.equals(cs.getReady()));
                final boolean proxyReady = statuses.stream()
                        .filter(cs -> "istio-proxy".equals(cs.getName()))
                        .anyMatch(cs -> Boolean.TRUE.equals(cs.getReady()));
                return serverReady && proxyReady;
            });
        });
        if (!ready) {
            throw new IllegalStateException(
                    "Timed out waiting for deployment '" + serviceName + "' to become ready");
        }
        logger.info("Deployment '{}' is ready.", serviceName);
    }
}
