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

import static com.linecorp.armeria.internal.common.zookeeper.ZooKeeperPathUtil.validatePath;
import static java.util.Objects.requireNonNull;

import java.util.Map;

import org.apache.zookeeper.CreateMode;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.zookeeper.ServerSetsInstance;

/**
 * Builds a {@link ZooKeeperRegistrationSpec} for
 * <a href="https://twitter.github.io/finagle/docs/com/twitter/serverset.html">Finagle ServerSets</a>.
 */
public final class ServerSetsRegistrationSpecBuilder {

    private static final String DEFAULT_NODE_NAME = "member_";

    @Nullable
    private Endpoint serviceEndpoint;
    private final ImmutableMap.Builder<String, Endpoint> additionalEndpointsBuilder = ImmutableMap.builder();
    @Nullable
    private Integer shardId;
    private Map<String, String> metadata = ImmutableMap.of();

    // https://github.com/twitter/finagle/blob/finagle-20.5.0/finagle-serversets/src/main/java/com/twitter/finagle/common/zookeeper/Group.java#L58
    // Finagle uses the sequential node with the prefix by default.
    // So the node name will be "member_0000000000", "member_0000000001" and so on.
    private String nodeName = DEFAULT_NODE_NAME;
    private boolean sequential = true;

    ServerSetsRegistrationSpecBuilder() {}

    /**
     * Sets the specified {@link Endpoint}.
     */
    public ServerSetsRegistrationSpecBuilder serviceEndpoint(Endpoint serviceEndpoint) {
        this.serviceEndpoint = requireNonNull(serviceEndpoint, "serviceEndpoint");
        return this;
    }

    /**
     * Adds the specified additional {@link Endpoint} with the specified {@code name}.
     */
    public ServerSetsRegistrationSpecBuilder additionalEndpoint(
            String name, Endpoint additionalEndpoint) {
        additionalEndpointsBuilder.put(requireNonNull(name, "name"),
                                       requireNonNull(additionalEndpoint, "additionalEndpoint"));
        return this;
    }

    /**
     * Adds the specified additional {@link Endpoint}s.
     */
    public ServerSetsRegistrationSpecBuilder additionalEndpoints(
            Map<String, Endpoint> additionalEndpoints) {
        requireNonNull(additionalEndpoints, "additionalEndpoints");
        additionalEndpointsBuilder.putAll(additionalEndpoints);
        return this;
    }

    /**
     * Sets the shard ID.
     */
    public ServerSetsRegistrationSpecBuilder shardId(int shardId) {
        this.shardId = shardId;
        return this;
    }

    /**
     * Sets the metadata.
     */
    public ServerSetsRegistrationSpecBuilder metadata(Map<String, String> metadata) {
        this.metadata = ImmutableMap.copyOf(requireNonNull(metadata, "metadata"));
        return this;
    }

    /**
     * Sets the specified {@code nodeName}. {@value DEFAULT_NODE_NAME} is used by default.
     */
    public ServerSetsRegistrationSpecBuilder nodeName(String nodeName) {
        this.nodeName = validatePath(nodeName, "nodeName");
        return this;
    }

    /**
     * Sets whether to create the ZooKeeper node using {@link CreateMode#EPHEMERAL_SEQUENTIAL} or not.
     * The default value is {@code true} that means the node will be created sequentially by appending
     * the sequence number to {@link #nodeName(String)}. For example, if the {@code nodeName} is
     * {@code "foo_"}, the nodes will be {@code "foo_0000000000"}, {@code "foo_0000000001"} and so on.
     */
    public ServerSetsRegistrationSpecBuilder sequential(boolean sequential) {
        this.sequential = sequential;
        return this;
    }

    /**
     * Returns a newly-created {@link ZooKeeperRegistrationSpec} based on the properties set so far.
     */
    public ZooKeeperRegistrationSpec build() {
        final ServerSetsInstance serverSetsInstance =
                new ServerSetsInstance(serviceEndpoint, additionalEndpointsBuilder.build(),
                                       shardId, metadata);
        return new ServerSetsRegistrationSpec(nodeName, sequential, serverSetsInstance);
    }
}
