/*
 * Copyright 2026 LINE Corporation
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

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Specifies how {@link KubernetesEndpointGroup} discovers endpoints from Kubernetes.
 */
@UnstableApi
public enum KubernetesEndpointMode {

    /**
     * Uses {@code nodeIP:nodePort} for endpoints. This is the default mode that relies on kube-proxy
     * for traffic routing. The Kubernetes service must have a type of
     * <a href="https://kubernetes.io/docs/concepts/services-networking/service/#type-nodeport">NodePort</a>
     * or <a href="https://kubernetes.io/docs/concepts/services-networking/service/#loadbalancer">LoadBalancer</a>.
     *
     * <p>This mode requires RBAC permissions for {@code pods}, {@code services}, and {@code nodes}.
     */
    NODE_PORT,

    /**
     * Uses {@code podIP:containerPort} for endpoints. This mode enables true client-side load balancing
     * by connecting directly to pod IPs, bypassing kube-proxy.
     *
     * <p>This mode is intended for Armeria clients running inside the Kubernetes cluster and requires
     * RBAC permissions for {@code pods} and {@code services} only (no {@code nodes} permission needed).
     */
    POD
}
