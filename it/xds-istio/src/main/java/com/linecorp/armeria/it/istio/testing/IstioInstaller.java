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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentCondition;
import io.fabric8.kubernetes.client.KubernetesClient;

final class IstioInstaller {

    static final Duration DEFAULT_READY_TIMEOUT = Duration.ofMinutes(5);
    static final String DEFAULT_NAMESPACE = "istio-system";

    private static final String ISTIO_NAMESPACE_ENV = "ISTIO_NAMESPACE";

    private static final Logger logger = LoggerFactory.getLogger(IstioInstaller.class);

    private IstioInstaller() {}

    // -- Installation --

    static void installIfNeeded(Path kubeconfigPath) throws Exception {
        installIfNeeded(kubeconfigPath, IstioEnv.istioProfile());
    }

    static void installIfNeeded(Path kubeconfigPath, String profile) throws Exception {
        final String version = IstioEnv.istioVersion();
        final String namespace = istioNamespace();

        try (KubernetesClient client = K8sClusterHelper.createClient(kubeconfigPath)) {
            if (isIstioInstalled(client, namespace)) {
                logger.info("Istio is already installed in namespace '{}'.", namespace);
                if (!waitForIstiodReady(client, namespace, DEFAULT_READY_TIMEOUT,
                                        K8sClusterHelper.DEFAULT_POLL_INTERVAL)) {
                    throw new IllegalStateException("Timed out waiting for Istio to be Ready.");
                }
                enableNamespaceInjection(client, "default");
                return;
            }
        }

        final Path istioctl = IstioEnv.istioctlPath();
        logger.info("Installing Istio {} with profile '{}'.", version, profile);
        runIstioctlInstall(istioctl, kubeconfigPath, profile);

        try (KubernetesClient client = K8sClusterHelper.createClient(kubeconfigPath)) {
            if (!waitForIstiodReady(client, namespace, DEFAULT_READY_TIMEOUT,
                                    K8sClusterHelper.DEFAULT_POLL_INTERVAL)) {
                throw new IllegalStateException("Timed out waiting for Istio to be Ready.");
            }
            enableNamespaceInjection(client, "default");
        }
    }

    private static void enableNamespaceInjection(KubernetesClient client, String namespaceName) {
        client.namespaces().withName(namespaceName).edit(ns -> {
            ns.getMetadata().getLabels().put("istio-injection", "enabled");
            return ns;
        });
        logger.info("Labeled namespace '{}' with istio-injection=enabled", namespaceName);
    }

    private static void runIstioctlInstall(Path istioctl, Path kubeconfigPath,
                                           String profile) throws Exception {
        final Path istioctlDir = requireParent(istioctl, "istioctl");
        runCommand(List.of(istioctl.toString(),
                           "install",
                           "--set", "profile=" + profile,
                           "--skip-confirmation",
                           "--kubeconfig", kubeconfigPath.toAbsolutePath().toString()),
                   istioctlDir,
                   "istioctl");
    }

    static void runIstioctlUninstall(Path kubeconfigPath) throws Exception {
        final Path istioctl = IstioEnv.istioctlPath();
        final Path istioctlDir = requireParent(istioctl, "istioctl");
        runCommand(List.of(istioctl.toString(),
                           "uninstall",
                           "--purge",
                           "-y",
                           "--kubeconfig", kubeconfigPath.toAbsolutePath().toString()),
                   istioctlDir,
                   "istioctl");
    }

    private static void runCommand(List<String> command, Path workDir, String logPrefix) throws Exception {
        final ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(true);

        final Process process = builder.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.info("[{}] {}", logPrefix, line);
            }
        }
        final int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Command failed (" + exitCode + "): " + command);
        }
    }

    // -- Status / waiting --

    private static boolean isIstioInstalled(KubernetesClient client, String namespace) {
        return client.apps().deployments().inNamespace(namespace).withName("istiod").get() != null;
    }

    static boolean waitForIstiodReady(KubernetesClient client) {
        return waitForIstiodReady(client, istioNamespace(), DEFAULT_READY_TIMEOUT,
                                  K8sClusterHelper.DEFAULT_POLL_INTERVAL);
    }

    private static boolean waitForIstiodReady(KubernetesClient client, String namespace,
                                              Duration timeout, Duration pollInterval) {
        return K8sClusterHelper.poll(timeout, pollInterval,
                                     () -> isDeploymentReady(client, namespace, "istiod"));
    }

    static boolean waitForIstioRemoval(KubernetesClient client) {
        return waitForIstioRemoval(client, istioNamespace(), DEFAULT_READY_TIMEOUT,
                                   K8sClusterHelper.DEFAULT_POLL_INTERVAL);
    }

    static boolean waitForIstioRemoval(KubernetesClient client, String namespace,
                                       Duration timeout, Duration pollInterval) {
        return K8sClusterHelper.poll(timeout, pollInterval, () -> {
            if (isIstioInstalled(client, namespace)) {
                return false;
            }
            return client.namespaces().withName(namespace).get() == null ||
                   client.pods().inNamespace(namespace).list().getItems().isEmpty();
        });
    }

    private static boolean isDeploymentReady(KubernetesClient client, String namespace, String name) {
        final Deployment deployment = client.apps().deployments().inNamespace(namespace).withName(name).get();
        if (deployment == null || deployment.getStatus() == null) {
            return false;
        }
        if (Boolean.TRUE.equals(hasAvailableCondition(deployment))) {
            return true;
        }
        final Integer availableReplicas = deployment.getStatus().getAvailableReplicas();
        return availableReplicas != null && availableReplicas > 0;
    }

    @Nullable
    private static Boolean hasAvailableCondition(Deployment deployment) {
        if (deployment.getStatus() == null || deployment.getStatus().getConditions() == null) {
            return null;
        }
        for (DeploymentCondition condition : deployment.getStatus().getConditions()) {
            if ("Available".equals(condition.getType()) && "True".equals(condition.getStatus())) {
                return true;
            }
        }
        return null;
    }

    // -- Configuration / utilities --

    private static String istioNamespace() {
        final String v = System.getenv(ISTIO_NAMESPACE_ENV);
        return (v != null && !v.isBlank()) ? v.trim() : DEFAULT_NAMESPACE;
    }

    private static Path requireParent(Path path, String description) {
        final Path parent = path.getParent();
        if (parent == null) {
            throw new IllegalStateException("Missing parent directory for " + description + ": " + path);
        }
        return parent;
    }
}
