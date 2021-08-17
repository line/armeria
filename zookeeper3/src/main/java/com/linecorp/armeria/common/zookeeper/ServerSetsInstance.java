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
// =================================================================================================
// Copyright 2011 Twitter, Inc.
// -------------------------------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this work except in compliance with the License.
// You may obtain a copy of the License in the LICENSE file, or at:
//
//  https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// =================================================================================================
package com.linecorp.armeria.common.zookeeper;

import static java.util.Objects.requireNonNull;

import java.util.Map;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.zookeeper.ServerSetsInstanceConverter.FinagleServiceInstanceDeserializer;
import com.linecorp.armeria.common.zookeeper.ServerSetsInstanceConverter.FinagleServiceInstanceSerializer;

/**
 * A class that represents a service instance used by
 * <a href="https://twitter.github.io/finagle/docs/com/twitter/serverset.html">Finagle ServerSets</a>.
 */
@JsonSerialize(using = FinagleServiceInstanceSerializer.class)
@JsonDeserialize(using = FinagleServiceInstanceDeserializer.class)
public final class ServerSetsInstance {

    // IDL is defined in https://github.com/twitter/finagle/blob/finagle-20.5.0/finagle-serversets/src/main/thrift/com/twitter/thrift/endpoint.thrift

    @Nullable
    private final Endpoint serviceEndpoint;
    private final Map<String, Endpoint> additionalEndpoints;
    @Nullable
    private final Integer shardId;
    private final Map<String, String> metadata;

    /**
     * Creates a new instance.
     */
    public ServerSetsInstance(
            @Nullable Endpoint serviceEndpoint, Map<String, Endpoint> additionalEndpoints,
            @Nullable Integer shardId, Map<String, String> metadata) {
        this.serviceEndpoint = serviceEndpoint;
        this.additionalEndpoints =
                ImmutableMap.copyOf(requireNonNull(additionalEndpoints, "additionalEndpoints"));
        this.shardId = shardId;
        this.metadata = ImmutableMap.copyOf(requireNonNull(metadata, "metadata"));
    }

    /**
     * Returns the service {@link Endpoint}.
     */
    @Nullable
    public Endpoint serviceEndpoint() {
        return serviceEndpoint;
    }

    /**
     * Returns the additional {@link Endpoint}s.
     */
    public Map<String, Endpoint> additionalEndpoints() {
        return additionalEndpoints;
    }

    /**
     * Returns the shard ID.
     */
    @Nullable
    public Integer shardId() {
        return shardId;
    }

    /**
     * Returns the metadata.
     */
    public Map<String, String> metadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ServerSetsInstance)) {
            return false;
        }
        final ServerSetsInstance that = (ServerSetsInstance) o;
        return Objects.equal(serviceEndpoint, that.serviceEndpoint) &&
               Objects.equal(additionalEndpoints, that.additionalEndpoints) &&
               Objects.equal(shardId, that.shardId) &&
               Objects.equal(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(serviceEndpoint, additionalEndpoints, shardId, metadata);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("serviceEndpoint", serviceEndpoint)
                          .add("additionalEndpoints", additionalEndpoints)
                          .add("shardId", shardId)
                          .add("metadata", metadata)
                          .toString();
    }
}
