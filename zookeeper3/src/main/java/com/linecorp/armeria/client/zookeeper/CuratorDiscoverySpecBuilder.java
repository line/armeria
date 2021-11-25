/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.client.zookeeper;

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.internal.common.zookeeper.ZooKeeperPathUtil.validatePath;
import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import org.apache.curator.x.discovery.ServiceInstance;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Builds a {@link ZooKeeperDiscoverySpec} for
 * <a href="https://curator.apache.org/curator-x-discovery/index.html">Curator Service Discovery</a>.
 */
public final class CuratorDiscoverySpecBuilder {

    private final String serviceName;
    @Nullable
    private String instanceId;
    @Nullable
    private Boolean useSsl;
    @Nullable
    private Function<? super ServiceInstance<?>, @Nullable Endpoint> converter;

    /**
     * Creates a new instance.
     */
    CuratorDiscoverySpecBuilder(String serviceName) {
        this.serviceName = validatePath(serviceName, "serviceName");
    }

    /**
     * Sets the specified instance ID. If this is set, the {@link ZooKeeperEndpointGroup} will only connect to
     * the instance.
     */
    public CuratorDiscoverySpecBuilder instanceId(String instanceId) {
        checkState(converter == null, "converter() and instanceId() are mutually exclusive.");
        this.instanceId = requireNonNull(instanceId, "instanceId");
        return this;
    }

    /**
     * Sets whether to connect an {@link Endpoint} using {@code sslPort} of {@link ServiceInstance}.
     */
    public CuratorDiscoverySpecBuilder useSsl(boolean useSsl) {
        checkState(converter == null, "converter() and useSsl() are mutually exclusive.");
        this.useSsl = useSsl;
        return this;
    }

    /**
     * Sets the specified converter to convert a {@link ServiceInstance} into an {@link Endpoint}.
     * If you don't want to connect to the service, you can simply return {@code null} in the converter.
     */
    public CuratorDiscoverySpecBuilder converter(
            Function<? super ServiceInstance<?>, Endpoint> converter) {
        checkState(instanceId == null, "converter() and instanceId() are mutually exclusive.");
        checkState(useSsl == null, "converter() and useSsl() are mutually exclusive.");
        this.converter = requireNonNull(converter, "converter");
        return this;
    }

    private Function<? super ServiceInstance<?>, @Nullable Endpoint> converter() {
        if (converter != null) {
            return converter;
        }
        return instance -> {
            if (!instance.isEnabled()) {
                return null;
            }
            if (instanceId != null && !instanceId.equals(instance.getId())) {
                return null;
            }
            if (useSsl != null && useSsl && instance.getSslPort() != null) {
                return Endpoint.of(instance.getAddress(), instance.getSslPort());
            }

            if (instance.getPort() != null) {
                return Endpoint.of(instance.getAddress(), instance.getPort());
            }
            return Endpoint.of(instance.getAddress());
        };
    }

    /**
     * Returns a newly-created {@link ZooKeeperDiscoverySpec} based on the properties set so far.
     */
    public ZooKeeperDiscoverySpec build() {
        return new CuratorDiscoverySpec(serviceName, converter());
    }
}
