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
package com.linecorp.armeria.server.zookeeper;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.internal.common.zookeeper.ZooKeeperPathUtil.validatePath;
import static java.util.Objects.requireNonNull;

import java.util.UUID;

import org.apache.curator.x.discovery.ServiceInstance;
import org.apache.curator.x.discovery.ServiceType;
import org.apache.curator.x.discovery.UriSpec;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Builds a {@link ZooKeeperRegistrationSpec} for
 * <a href="https://curator.apache.org/curator-x-discovery/index.html">Curator Service Discovery</a>.
 */
public final class CuratorRegistrationSpecBuilder {

    private final String serviceName;
    @Nullable
    private String serviceId;
    @Nullable
    private String serviceAddress;
    @Nullable
    private Integer port;
    @Nullable
    private Integer sslPort;
    private ServiceType serviceType = ServiceType.DYNAMIC;
    @Nullable
    private Object payload;

    private UriSpec uriSpec = new UriSpec("{scheme}://{address}:{port}");

    /**
     * Creates a new instance.
     */
    CuratorRegistrationSpecBuilder(String serviceName) {
        this.serviceName = validatePath(serviceName, "serviceName");
    }

    /**
     * Sets the service address.
     */
    public CuratorRegistrationSpecBuilder serviceAddress(String serviceAddress) {
        this.serviceAddress = requireNonNull(serviceAddress, "serviceAddress");
        return this;
    }

    /**
     * Sets the port.
     */
    public CuratorRegistrationSpecBuilder port(int port) {
        checkArgument(port > 0, "port: %s (expected: > 0)", port);
        this.port = port;
        return this;
    }

    /**
     * Sets the SSL port.
     */
    public CuratorRegistrationSpecBuilder sslPort(int sslPort) {
        checkArgument(sslPort > 0, "sslPort: %s (expected: > 0)", sslPort);
        this.sslPort = sslPort;
        return this;
    }

    /**
     * Sets the service ID.
     */
    public CuratorRegistrationSpecBuilder serviceId(String serviceId) {
        this.serviceId = validatePath(serviceId, "serviceId");
        return this;
    }

    /**
     * Sets the payload.
     */
    public <T> CuratorRegistrationSpecBuilder payload(T payload) {
        this.payload = requireNonNull(payload, "payload");
        return this;
    }

    /**
     * Sets the {@link ServiceType}.
     */
    public CuratorRegistrationSpecBuilder serviceType(ServiceType serviceType) {
        this.serviceType = requireNonNull(serviceType, "serviceType");
        return this;
    }

    /**
     * Sets the {@link UriSpec}.
     */
    public CuratorRegistrationSpecBuilder uriSpec(UriSpec uriSpec) {
        this.uriSpec = requireNonNull(uriSpec, "uriSpec");
        return this;
    }

    /**
     * Returns a newly-created {@link ZooKeeperRegistrationSpec} based on the properties set so far.
     */
    public ZooKeeperRegistrationSpec build() {
        final String serviceId = this.serviceId != null ? this.serviceId : UUID.randomUUID().toString();
        final ServiceInstance<?> serviceInstance =
                new ServiceInstance<>(serviceName, serviceId, serviceAddress, port, sslPort,
                                      payload, System.currentTimeMillis(), serviceType, uriSpec);
        return new CuratorRegistrationSpec(serviceInstance);
    }
}
