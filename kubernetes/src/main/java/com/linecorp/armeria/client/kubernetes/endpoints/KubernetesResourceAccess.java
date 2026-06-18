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

package com.linecorp.armeria.client.kubernetes.endpoints;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.netty.util.AttributeKey;

/**
 * Provides access to the Kubernetes resources that a {@link KubernetesEndpointGroup} attaches to each
 * {@link Endpoint} it creates.
 *
 * <p>A {@link KubernetesEndpointGroup} stores the {@link Pod} an {@link Endpoint} was derived from, and
 * additionally the {@link Node} the {@link Pod} is running on in {@link KubernetesEndpointMode#NODE_PORT}
 * mode, as {@linkplain Endpoint#attrs() endpoint attributes}. This class exposes them so that, for example,
 * load balancing or logging logic can inspect pod labels, annotations or node metadata:
 * <pre>{@code
 * KubernetesEndpointGroup endpointGroup = ...;
 * for (Endpoint endpoint : endpointGroup.endpoints()) {
 *     Pod pod = KubernetesResourceAccess.pod(endpoint);
 *     if (pod != null) {
 *         String zone = pod.getMetadata().getLabels().get("topology.kubernetes.io/zone");
 *         ...
 *     }
 * }
 * }</pre>
 */
@UnstableApi
public final class KubernetesResourceAccess {

    static final AttributeKey<Node> NODE_KEY =
            AttributeKey.valueOf(KubernetesResourceAccess.class, "NODE_KEY");

    static final AttributeKey<Pod> POD_KEY =
            AttributeKey.valueOf(KubernetesResourceAccess.class, "POD_KEY");

    /**
     * Returns the {@link Node} the specified {@link Endpoint} was derived from, or {@code null} if the
     * {@link Endpoint} has no associated {@link Node}.
     *
     * <p>A {@link Node} is attached only to {@link Endpoint}s created by a {@link KubernetesEndpointGroup} in
     * {@link KubernetesEndpointMode#NODE_PORT} mode. {@code null} is returned for {@link Endpoint}s created in
     * {@link KubernetesEndpointMode#POD} mode, and for {@link Endpoint}s that did not originate from a
     * {@link KubernetesEndpointGroup}.
     */
    @Nullable
    public static Node node(Endpoint endpoint) {
        requireNonNull(endpoint, "endpoint");
        return endpoint.attr(NODE_KEY);
    }

    /**
     * Returns the {@link Pod} the specified {@link Endpoint} was derived from, or {@code null} if the
     * {@link Endpoint} has no associated {@link Pod}.
     *
     * <p>A {@link Pod} is attached to every {@link Endpoint} created by a {@link KubernetesEndpointGroup},
     * regardless of its {@link KubernetesEndpointMode}. {@code null} is returned only for {@link Endpoint}s
     * that did not originate from a {@link KubernetesEndpointGroup}.
     */
    @Nullable
    public static Pod pod(Endpoint endpoint) {
        requireNonNull(endpoint, "endpoint");
        return endpoint.attr(POD_KEY);
    }

    private KubernetesResourceAccess() {}
}
