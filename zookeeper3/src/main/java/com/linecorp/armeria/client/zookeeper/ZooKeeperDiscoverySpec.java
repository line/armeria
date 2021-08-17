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

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.zookeeper.ServerSetsInstance;
import com.linecorp.armeria.server.zookeeper.ZooKeeperRegistrationSpec;

/**
 * A discovery specification for {@link ZooKeeperEndpointGroup}. The specification is used for finding
 * and decoding the registered instances into {@link Endpoint}s.
 *
 * @see ZooKeeperRegistrationSpec
 */
public interface ZooKeeperDiscoverySpec {

    /**
     * Returns a {@link ZooKeeperDiscoverySpec} that is compatible with
     * <a href="https://curator.apache.org/curator-x-discovery/index.html">Curator Service Discovery</a>.
     * This is also compatible with
     * <a href="https://cloud.spring.io/spring-cloud-zookeeper/reference/html/">Spring Cloud Zookeeper</a>.
     *
     * @see ZooKeeperRegistrationSpec#curator(String)
     */
    static ZooKeeperDiscoverySpec curator(String serviceName) {
        return builderForCurator(serviceName).build();
    }

    /**
     * Returns a new {@link CuratorDiscoverySpecBuilder}. The specification is compatible with
     * <a href="https://curator.apache.org/curator-x-discovery/index.html">Curator Service Discovery</a> and
     * <a href="https://cloud.spring.io/spring-cloud-zookeeper/reference/html/">Spring Cloud Zookeeper</a>.
     *
     * @see ZooKeeperRegistrationSpec#builderForCurator(String)
     */
    static CuratorDiscoverySpecBuilder builderForCurator(String serviceName) {
        return new CuratorDiscoverySpecBuilder(serviceName);
    }

    /**
     * Returns a {@link ZooKeeperDiscoverySpec} that is compatible with
     * <a href="https://twitter.github.io/finagle/docs/com/twitter/serverset.html">Finagle ServerSets</a>.
     *
     * @see ZooKeeperRegistrationSpec#builderForServerSets()
     */
    static ZooKeeperDiscoverySpec serverSets() {
        return serverSets(ServerSetsInstance::serviceEndpoint);
    }

    /**
     * Returns a {@link ZooKeeperDiscoverySpec} that is compatible with
     * <a href="https://twitter.github.io/finagle/docs/com/twitter/serverset.html">Finagle ServerSets</a>.
     *
     * @param converter the converter to convert a {@link ServerSetsInstance} to an {@link Endpoint}.
     *                  If you don't want to connect to the service, you can simply return
     *                  {@code null} in the converter.
     *
     * @see ZooKeeperRegistrationSpec#builderForServerSets()
     */
    static ZooKeeperDiscoverySpec serverSets(Function<? super ServerSetsInstance, Endpoint> converter) {
        return new ServerSetsDiscoverySpec(requireNonNull(converter, "converter"));
    }

    /**
     * Returns the legacy {@link ZooKeeperDiscoverySpec} implementation which assumes a znode value is
     * a comma-separated string. Each element of the znode value represents an {@link Endpoint} whose format is
     * {@code <host>[:<port_number>[:weight]]}, such as:
     * <ul>
     *   <li>{@code "foo.com"} - default port number, default weight (1000)</li>
     *   <li>{@code "bar.com:8080} - port number 8080, default weight (1000)</li>
     *   <li>{@code "10.0.2.15:0:500} - default port number, weight 500</li>
     *   <li>{@code "192.168.1.2:8443:700} - port number 8443, weight 700</li>
     * </ul>
     * Note that the port number must be specified when you want to specify the weight.
     *
     * @see ZooKeeperRegistrationSpec#legacy()
     */
    static ZooKeeperDiscoverySpec legacy() {
        return LegacyZooKeeperDiscoverySpec.INSTANCE;
    }

    /**
     * Returns the path for finding the byte array representation of registered instances. The path is appended
     * to the {@code znodePath} that is specified when creating {@link ZooKeeperEndpointGroup}.
     */
    @Nullable
    String path();

    /**
     * Decodes a znode value to an {@link Endpoint}.
     */
    @Nullable
    Endpoint decode(byte[] data);
}
