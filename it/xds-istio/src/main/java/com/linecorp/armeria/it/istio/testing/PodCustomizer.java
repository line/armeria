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
 * Pluggable hook that applies pod-level customizations (annotations, volume mounts, etc.)
 * before a test pod is submitted to Kubernetes. Implement this interface to inject
 * sidecar-specific or environment-specific configuration without modifying
 * {@link IstioTestExtension} directly.
 *
 * <p>Specify the implementation via {@link IstioPodTest#podCustomizer()}.
 * The default is {@link IstioPodCustomizer}.
 */
public interface PodCustomizer {

    /**
     * Applies customizations to the pod under construction.
     * The base pod already contains a container named {@code "test"} with the test entry point.
     */
    void customizePod(PodBuilder podBuilder);

    /**
     * Returns {@code true} if the pod is healthy enough for the test container to start running.
     * The default implementation simply checks that the pod phase is {@code Running}.
     */
    default boolean isPodHealthy(Pod pod) {
        return "Running".equals(pod.getStatus().getPhase());
    }
}
