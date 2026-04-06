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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.k3s.K3sContainer;

import com.linecorp.armeria.common.util.SafeCloseable;

import io.fabric8.kubernetes.client.KubernetesClient;

final class IstioState implements SafeCloseable {

    private static final Logger logger = LoggerFactory.getLogger(IstioState.class);

    private final KubernetesClient client;
    private final Path kubeconfigPath;

    private IstioState(KubernetesClient client, Path kubeconfigPath) {
        this.client = client;
        this.kubeconfigPath = kubeconfigPath;
    }

    static IstioState connectOrCreate() throws Exception {
        final Path kubeconfigPath = IstioEnv.kubeconfigPath();
        final KubernetesClient client;

        if (Files.exists(kubeconfigPath)) {
            final KubernetesClient existing = K8sClusterHelper.createClient(kubeconfigPath);
            KubernetesClient connected = null;
            try {
                existing.namespaces().list();
                logger.info("Successfully connected to existing K3s cluster");
                connected = existing;
            } catch (Exception e) {
                logger.warn("Failed to connect to existing cluster, creating a new one", e);
                existing.close();
            }
            if (connected != null) {
                client = connected;
            } else {
                client = startFreshCluster(kubeconfigPath);
            }
        } else {
            logger.info("No kubeconfig found, creating new cluster");
            client = startFreshCluster(kubeconfigPath);
        }

        reinstallIstio(kubeconfigPath, client);
        return new IstioState(client, kubeconfigPath);
    }

    KubernetesClient client() {
        return client;
    }

    Path kubeconfigPath() {
        return kubeconfigPath;
    }

    @Override
    public void close() {
        client.close();
    }

    private static KubernetesClient startFreshCluster(Path kubeconfigPath) throws Exception {
        final Path parent = kubeconfigPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        logger.info("Starting new K3s cluster...");
        final K3sContainer k3sContainer = K8sClusterHelper.startK3sAndWaitReady();
        Files.writeString(kubeconfigPath, k3sContainer.getKubeConfigYaml(), StandardCharsets.UTF_8);
        logger.info("K3s cluster started with container ID: {}", k3sContainer.getContainerId());
        return K8sClusterHelper.createClient(kubeconfigPath);
    }

    private static void reinstallIstio(Path kubeconfigPath, KubernetesClient client) throws Exception {
        logger.info("Uninstalling existing Istio installation...");
        IstioInstaller.runIstioctlUninstall(kubeconfigPath);

        logger.info("Waiting for Istio resources to be removed...");
        if (!IstioInstaller.waitForIstioRemoval(client)) {
            throw new IllegalStateException("Timed out waiting for Istio to be removed");
        }

        IstioInstaller.installIfNeeded(kubeconfigPath);

        if (!IstioInstaller.waitForIstiodReady(client)) {
            throw new IllegalStateException("Istio failed to become ready after reinstallation");
        }
    }
}
