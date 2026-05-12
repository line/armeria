/*
 * Copyright 2026 LY Corporation
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
 * A {@link PodCustomizer} for Istio gRPC proxyless mode. Uses the
 * {@code inject.istio.io/templates: grpc-agent} annotation which injects a lightweight
 * {@code pilot-agent} sidecar (no Envoy) that generates bootstrap files and manages
 * certificates for non-Envoy gRPC/xDS clients.
 *
 * <p>The {@code grpc-agent} template causes Istiod to use the {@code grpc} generator,
 * producing xDS configs with FQDN-based listener names
 * (e.g. {@code echo-server.default.svc.cluster.local:8080}) instead of IP-based names.
 */
public final class GrpcProxylessPodCustomizer implements PodCustomizer {

    @Override
    public void customizePod(PodBuilder podBuilder) {
        podBuilder.editMetadata()
                  .addToAnnotations("inject.istio.io/templates", "grpc-agent")
                  .endMetadata();
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
                    "istio-proxy container — grpc-agent injection did not occur");
        }
        return statuses.stream()
                       .filter(cs -> "istio-proxy".equals(cs.getName()))
                       .anyMatch(cs -> Boolean.TRUE.equals(cs.getReady()));
    }
}
