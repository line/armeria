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

import javax.annotation.Nullable;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.server.zookeeper.ZookeeperRegistrationSpec;

/**
 * A discovery specification for {@link ZooKeeperEndpointGroup}. The specification is used for finding
 * and decoding the registered instances into {@link Endpoint}s.
 *
 * @see ZookeeperRegistrationSpec
 */
public interface ZookeeperDiscoverySpec {

    /**
     * Returns a {@link ZookeeperDiscoverySpec} that is compatible with
     * <a href="https://curator.apache.org/curator-x-discovery/index.html">Curator Service Discovery</a>.
     * This is also compatible with
     * <a href="https://cloud.spring.io/spring-cloud-zookeeper/reference/html/">Spring Cloud Zookeeper</a>.
     */
    static ZookeeperDiscoverySpec curator(String serviceName) {
        return builderForCurator(serviceName).build();
    }

    /**
     * Returns a new {@link CuratorDiscoverySpecBuilder}. The specification is compatible with
     * <a href="https://curator.apache.org/curator-x-discovery/index.html">Curator Service Discovery</a> and
     * <a href="https://cloud.spring.io/spring-cloud-zookeeper/reference/html/">Spring Cloud Zookeeper</a>.
     */
    static CuratorDiscoverySpecBuilder builderForCurator(String serviceName) {
        return new CuratorDiscoverySpecBuilder(serviceName);
    }

    /**
     * Returns the legacy {@link ZookeeperDiscoverySpec} implementation which assumes a zNode value is
     * a comma-separated string. Each element of the zNode value represents an {@link Endpoint} whose format is
     * {@code <host>[:<port_number>[:weight]]}, such as:
     * <ul>
     *   <li>{@code "foo.com"} - default port number, default weight (1000)</li>
     *   <li>{@code "bar.com:8080} - port number 8080, default weight (1000)</li>
     *   <li>{@code "10.0.2.15:0:500} - default port number, weight 500</li>
     *   <li>{@code "192.168.1.2:8443:700} - port number 8443, weight 700</li>
     * </ul>
     * Note that the port number must be specified when you want to specify the weight.
     */
    static ZookeeperDiscoverySpec legacy() {
        return LegacyZookeeperDiscoverySpec.INSTANCE;
    }

    /**
     * Returns the path for finding the byte array representation of registered instances. The path is appended
     * to the {@code zNodePath} that is specified when creating {@link ZooKeeperEndpointGroup}.
     */
    @Nullable
    String path();

    /**
     * Decodes a zNode value to an {@link Endpoint}.
     */
    @Nullable
    Endpoint decode(byte[] data);
}
