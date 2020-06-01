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

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.zookeeper.ZookeeperDiscoverySpec;
import com.linecorp.armeria.server.Server;

/**
 * A registration specification for {@link ZooKeeperUpdatingListener}. The specification is used for encoding
 * and registering the {@link Server} to <a href="https://zookeeper.apache.org/">ZooKeeper</a>.
 *
 * @see ZookeeperDiscoverySpec
 */
public interface ZookeeperRegistrationSpec {

    /**
     * Returns the {@link ZookeeperRegistrationSpec} that registers the {@link Server} using
     * <a href="https://curator.apache.org/curator-x-discovery/index.html">Curator-X-Discovery</a>.
     * This is also, compatible with
     * <a href="https://cloud.spring.io/spring-cloud-zookeeper/reference/html/">Spring Cloud Zookeeper</a>.
     *
     * @see ZookeeperDiscoverySpec#ofCuratorX(String)
     */
    static ZookeeperRegistrationSpec ofCuratorXRegistration(String serviceName) {
        return new CuratorXZookeeperRegistrationSpecBuilder(serviceName).build();
    }

    /**
     * Returns a new {@link CuratorXZookeeperRegistrationSpecBuilder}. The specification is compatible with
     * <a href="https://curator.apache.org/curator-x-discovery/index.html">Curator-X-Discovery</a> and
     * <a href="https://cloud.spring.io/spring-cloud-zookeeper/reference/html/">Spring Cloud Zookeeper</a>.
     *
     * @see ZookeeperDiscoverySpec#builderForCuratorX(String)
     */
    static CuratorXZookeeperRegistrationSpecBuilder builderForCuratorX(String serviceName) {
        return new CuratorXZookeeperRegistrationSpecBuilder(serviceName);
    }

    /**
     * Returns the {@link ZookeeperRegistrationSpec} that registers the {@link Server} using the specified
     * {@link Endpoint}. The {@link Endpoint} is encoded to a comma-separated string whose format is
     * {@code <host>[:<port_number>[:weight]]}, such as:
     * <ul>
     *   <li>{@code "foo.com"} - default port number, default weight (1000)</li>
     *   <li>{@code "bar.com:8080} - port number 8080, default weight (1000)</li>
     *   <li>{@code "10.0.2.15:0:500} - default port number, weight 500</li>
     *   <li>{@code "192.168.1.2:8443:700} - port number 8443, weight 700</li>
     * </ul>
     * Note that the port number must be specified when you want to specify the weight.
     *
     * @see ZookeeperDiscoverySpec#ofLegacy()
     */
    static ZookeeperRegistrationSpec ofLegacy(Endpoint endpoint) {
        return new LegacyZookeeperRegistrationSpec(endpoint);
    }

    /**
     * Returns the path for registering the {@link Server}. The path is appended to the
     * {@code zNodePath} that is specified when creating {@link ZooKeeperUpdatingListener}.
     */
    String path();

    /**
     * Returns the byte array representation of the {@link Server}.
     */
    byte[] encodedInstance();
}
