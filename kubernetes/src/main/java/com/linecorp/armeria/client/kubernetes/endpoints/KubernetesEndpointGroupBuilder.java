/*
 * Copyright 2024 LINE Corporation
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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.function.Predicate;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.AbstractDynamicEndpointGroupBuilder;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.fabric8.kubernetes.api.model.NodeAddress;
import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * A builder for creating a new {@link KubernetesEndpointGroup}.
 */
@UnstableApi
public final class KubernetesEndpointGroupBuilder
        extends AbstractDynamicEndpointGroupBuilder<KubernetesEndpointGroupBuilder> {

    private final KubernetesClient kubernetesClient;
    private final boolean autoClose;
    private EndpointSelectionStrategy selectionStrategy = EndpointSelectionStrategy.weightedRoundRobin();

    @Nullable
    private String namespace;
    @Nullable
    private String serviceName;
    @Nullable
    private String portName;

    private Predicate<? super NodeAddress> nodeAddressFilter = nodeAddress ->
            "InternalIP".equals(nodeAddress.getType()) && !Strings.isNullOrEmpty(nodeAddress.getAddress());

    KubernetesEndpointGroupBuilder(KubernetesClient kubernetesClient, boolean autoClose) {
        super(Flags.defaultResponseTimeoutMillis());
        allowEmptyEndpoints(false);
        this.kubernetesClient = requireNonNull(kubernetesClient, "kubernetesClient");
        this.autoClose = autoClose;
    }

    /**
     * Sets the <a href="https://kubernetes.io/docs/concepts/overview/working-with-objects/namespaces/">namespace</a>
     * of a Kubernetes cluster.
     */
    public KubernetesEndpointGroupBuilder namespace(String namespace) {
        this.namespace = requireNonNull(namespace, "namespace");
        return this;
    }

    /**
     * Sets the target <a href="https://kubernetes.io/docs/concepts/services-networking/service/">service</a>
     * name from which {@link Endpoint}s should be fetched.
     */
    public KubernetesEndpointGroupBuilder serviceName(String serviceName) {
        this.serviceName = requireNonNull(serviceName, "serviceName");
        return this;
    }

    /**
     * Sets the name of the <a href="https://kubernetes.io/docs/concepts/services-networking/service/#field-spec-ports">port</a>
     * from which <a href="https://kubernetes.io/docs/concepts/services-networking/service/#type-nodeport">NodePort</a>
     * should be fetched from. If not set, the first node port will be used.
     */
    public KubernetesEndpointGroupBuilder portName(String portName) {
        this.portName = requireNonNull(portName, "portName");
        return this;
    }

    /**
     * Sets the {@link Predicate} to filter the <a href="https://kubernetes.io/docs/reference/node/node-status/#addresses">addresses</a>
     * of a Kubernetes node.
     * The first selected {@link NodeAddress} of a node will be used to create the {@link Endpoint}.
     * If unspecified, the default is to select an {@code InternalIP} address that is not empty.
     */
    public KubernetesEndpointGroupBuilder nodeAddressFilter(Predicate<? super NodeAddress> nodeAddressFilter) {
        requireNonNull(nodeAddressFilter, "nodeAddressFilter");
        this.nodeAddressFilter = nodeAddressFilter;
        return this;
    }

    /**
     * Sets the {@link EndpointSelectionStrategy} of the {@link KubernetesEndpointGroupBuilder}.
     */
    public KubernetesEndpointGroupBuilder selectionStrategy(EndpointSelectionStrategy selectionStrategy) {
        this.selectionStrategy = requireNonNull(selectionStrategy, "selectionStrategy");
        return this;
    }

    /**
     * Returns a newly-created {@link KubernetesEndpointGroup} based on the properties of this builder.
     */
    public KubernetesEndpointGroup build() {
        checkState(serviceName != null, "serviceName not set");
        return new KubernetesEndpointGroup(kubernetesClient, namespace, serviceName, portName,
                                           nodeAddressFilter, autoClose,
                                           selectionStrategy, shouldAllowEmptyEndpoints(),
                                           selectionTimeoutMillis());
    }
}
