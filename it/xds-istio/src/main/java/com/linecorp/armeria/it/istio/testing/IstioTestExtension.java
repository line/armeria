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

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * JUnit 5 extension that intercepts {@link IstioPodTest}-annotated methods and runs them
 * inside a Kubernetes Job in the K3s cluster instead of locally.
 *
 * <p>Requires {@link IstioClusterExtension} to be registered on the same test class so
 * the {@link KubernetesClient} and K3s container are available in the {@link ExtensionContext.Store}.
 */
public final class IstioTestExtension implements InvocationInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(IstioTestExtension.class);

    @Override
    public void interceptTestMethod(Invocation<Void> invocation,
                                    ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext extensionContext) throws Throwable {
        // When running inside the K8s Job itself, execute the test body normally.
        if (Boolean.parseBoolean(System.getenv(HostOnlyExtension.RUNNING_IN_K8S_POD_ENV))) {
            invocation.proceed();
            return;
        }

        invocation.skip();

        final ExtensionContext classContext = extensionContext.getParent()
                .orElseThrow(() -> new IllegalStateException("No parent context found"));
        final ExtensionContext.Store store = classContext.getStore(IstioClusterExtension.NAMESPACE);

        final KubernetesClient client = store.get(IstioClusterExtension.K8S_CLIENT_KEY,
                                                   KubernetesClient.class);
        if (client == null) {
            throw new IllegalStateException(
                    "KubernetesClient not found in store. " +
                    "Ensure IstioClusterExtension is registered on this test class.");
        }

        // Instantiate the PodCustomizer specified in the @IstioPodTest annotation.
        final IstioPodTest annotation = invocationContext.getExecutable()
                                                         .getAnnotation(IstioPodTest.class);
        final PodCustomizer podCustomizer;
        try {
            final Class<? extends PodCustomizer> customizerClass =
                    annotation != null ? annotation.podCustomizer() : IstioPodCustomizer.class;
            podCustomizer = customizerClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to instantiate PodCustomizer", e);
        }

        final String testClass = invocationContext.getTargetClass().getName();
        final String testMethod = invocationContext.getExecutable().getName();
        final String namespace = "default";
        final String podName = createTestPod(client, namespace, testClass, testMethod, podCustomizer);

        logger.info("Created K8s Pod '{}' for {}.{}", podName, testClass, testMethod);

        try {
            waitForPodHealthy(client, podName, namespace, podCustomizer);
            final int exitCode = waitForTestContainerTerminated(client, podName, namespace);
            final String logs = collectPodLogs(client, podName, namespace);
            if (exitCode == 0) {
                logger.info("Pod '{}' succeeded for {}.{}\nPod logs:\n{}",
                            podName, testClass, testMethod, logs);
            } else {
                logger.error("Pod '{}' failed (exit {}) for {}.{}\nPod logs:\n{}",
                             podName, exitCode, testClass, testMethod, logs);
                final String serverLogs = collectServerPodLogs(client, namespace);
                throw new AssertionError(
                        "Istio test pod failed for " + testClass + "#" + testMethod +
                        "\nPod logs:\n" + logs +
                        "\nServer pod logs:\n" + serverLogs);
            }
        } finally {
            client.pods().inNamespace(namespace).withName(podName).delete();
        }
    }

    private static String createTestPod(KubernetesClient client, String namespace,
                                         String testClass, String testMethod,
                                         PodCustomizer podCustomizer) {
        final String podName = "istio-test-" +
                               UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        final PodBuilder builder = new PodBuilder()
                .withNewMetadata()
                    .withName(podName)
                    .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                    .withRestartPolicy("Never")
                    .addNewContainer()
                        .withName("test")
                        .withImage(IstioTestImage.IMAGE_NAME)
                        .withImagePullPolicy("Never")
                        .withArgs("--class", testClass, "--method", testMethod)
                        .addNewEnv()
                            .withName(HostOnlyExtension.RUNNING_IN_K8S_POD_ENV)
                            .withValue("true")
                        .endEnv()
                        .addNewEnv()
                            .withName("JAVA_TOOL_OPTIONS")
                            .withValue(IstioEnv.podJvmArgs())
                        .endEnv()
                    .endContainer()
                .endSpec();

        podCustomizer.customizePod(builder);

        client.pods().inNamespace(namespace).resource(builder.build()).create();
        return podName;
    }

    private static void waitForPodHealthy(KubernetesClient client,
                                           String podName, String namespace,
                                           PodCustomizer podCustomizer) {
        final boolean healthy = K8sClusterHelper.poll(
                Duration.ofMinutes(5), K8sClusterHelper.DEFAULT_POLL_INTERVAL, () -> {
            final Pod pod = client.pods().inNamespace(namespace).withName(podName).get();
            if (pod == null || pod.getStatus() == null) {
                return false;
            }
            return podCustomizer.isPodHealthy(pod);
        });
        if (!healthy) {
            throw new IllegalStateException(
                    "Timed out waiting for pod '" + podName + "' to become healthy");
        }
        logger.info("Pod '{}' is healthy", podName);
    }

    private static int waitForTestContainerTerminated(KubernetesClient client,
                                                       String podName, String namespace) {
        final int[] exitCode = {1};
        final boolean terminated = K8sClusterHelper.poll(
                Duration.ofMinutes(5), K8sClusterHelper.DEFAULT_POLL_INTERVAL, () -> {
            final Pod pod = client.pods().inNamespace(namespace).withName(podName).get();
            if (pod == null || pod.getStatus() == null ||
                pod.getStatus().getContainerStatuses() == null) {
                return false;
            }
            return pod.getStatus().getContainerStatuses().stream()
                      .filter(cs -> "test".equals(cs.getName()))
                      .filter(cs -> cs.getState() != null && cs.getState().getTerminated() != null)
                      .findFirst()
                      .map(cs -> {
                          exitCode[0] = cs.getState().getTerminated().getExitCode();
                          return true;
                      })
                      .orElse(false);
        });
        if (!terminated) {
            logger.warn("Timed out waiting for test container in pod '{}' to terminate", podName);
        }
        return exitCode[0];
    }

    private static String collectPodLogs(KubernetesClient client,
                                          String podName, String namespace) {
        try {
            return client.pods().inNamespace(namespace).withName(podName)
                         .inContainer("test").getLog();
        } catch (Exception e) {
            return "Failed to retrieve logs: " + e.getMessage();
        }
    }

    private static String collectServerPodLogs(KubernetesClient client, String namespace) {
        final StringBuilder sb = new StringBuilder();
        try {
            client.pods().inNamespace(namespace)
                  .withLabel("app")
                  .list().getItems()
                  .forEach(pod -> {
                      final String pName = pod.getMetadata().getName();
                      pod.getSpec().getContainers().forEach(c -> {
                          try {
                              final String podLogs = client.pods().inNamespace(namespace)
                                                           .withName(pName)
                                                           .inContainer(c.getName()).getLog();
                              sb.append("\n=== Pod '").append(pName)
                                .append("' container '").append(c.getName()).append("' ===\n")
                                .append(podLogs);
                          } catch (Exception e) {
                              sb.append("\n[Failed to get logs for pod '").append(pName)
                                .append("' container '").append(c.getName()).append("': ")
                                .append(e.getMessage()).append(']');
                          }
                      });
                  });
        } catch (Exception e) {
            sb.append("[Failed to list server pods: ").append(e.getMessage()).append(']');
        }
        return sb.toString();
    }
}
