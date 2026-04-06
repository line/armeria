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

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;

/**
 * Default {@link PodCustomizer} for Istio-injected pods. Adds the Istio sidecar injection
 * annotation and mounts the UDS socket volumes that {@code pilot-agent} exposes, so the
 * test container can reach the xDS and SDS APIs.
 *
 * <p>This is the default value for {@link IstioPodTest#podCustomizer()}.
 */
public final class IstioPodCustomizer implements PodCustomizer {

    @Override
    public void customizePod(PodBuilder podBuilder) {
        podBuilder.editMetadata()
                  .addToAnnotations("sidecar.istio.io/inject", "true")
                  .endMetadata()
                  .editSpec()
                  .editMatchingContainer(c -> "test".equals(c.getName()))
                  // Mount volumes injected by the Istio mutating webhook so that
                  // the test container can access pilot-agent's UDS sockets.
                  .addNewVolumeMount()
                  .withName("workload-socket")
                  .withMountPath("/var/run/secrets/workload-spiffe-uds")
                  .endVolumeMount()
                  .addNewVolumeMount()
                  .withName("istio-envoy")
                  .withMountPath("/etc/istio/proxy")
                  .endVolumeMount()
                  .endContainer()
                  .endSpec();
    }

    @Override
    public boolean isPodHealthy(Pod pod) {
        if (pod.getStatus() == null || !"Running".equals(pod.getStatus().getPhase())) {
            return false;
        }
        final var statuses = pod.getStatus().getContainerStatuses();
        if (statuses == null) {
            return false;
        }
        if (statuses.stream().noneMatch(cs -> "istio-proxy".equals(cs.getName()))) {
            throw new IllegalStateException(
                    "Pod '" + pod.getMetadata().getName() + "' is running but has no " +
                    "istio-proxy container — sidecar injection did not occur");
        }
        return statuses.stream()
                       .filter(cs -> "istio-proxy".equals(cs.getName()))
                       .anyMatch(cs -> Boolean.TRUE.equals(cs.getReady()));
    }
}
