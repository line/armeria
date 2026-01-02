/*
 * Copyright 2024 LY Corporation
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
package com.linecorp.armeria.server.nacos;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.URI;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.nacos.NacosConfigSetters;
import com.linecorp.armeria.internal.nacos.NacosClient;
import com.linecorp.armeria.internal.nacos.NacosClientBuilder;
import com.linecorp.armeria.server.Server;

/**
 * Builds a new {@link NacosUpdatingListener}, which registers the server to Nacos.
 * <h2>Examples</h2>
 * <pre>{@code
 * NacosUpdatingListener listener = NacosUpdatingListener.builder(nacosUri, "myService")
 *                                                       .build();
 * ServerBuilder sb = Server.builder();
 * sb.serverListener(listener);
 * }</pre>
 */
@UnstableApi
public final class NacosUpdatingListenerBuilder implements NacosConfigSetters<NacosUpdatingListenerBuilder> {

    private final NacosClientBuilder nacosClientBuilder;
    @Nullable
    private Endpoint endpoint;

    /**
     * Creates a {@link NacosUpdatingListenerBuilder} with a service name.
     */
    NacosUpdatingListenerBuilder(URI nacosUri, String serviceName) {
        requireNonNull(serviceName, "serviceName");
        checkArgument(!serviceName.isEmpty(), "serviceName can't be empty");
        nacosClientBuilder = NacosClient.builder(nacosUri, serviceName);
    }

    /**
     * Sets the {@link Endpoint} to register. If not set, the current host name is used by default.
     */
    public NacosUpdatingListenerBuilder endpoint(Endpoint endpoint) {
        this.endpoint = requireNonNull(endpoint, "endpoint");
        return this;
    }

    @Override
    public NacosUpdatingListenerBuilder namespaceId(String namespaceId) {
        nacosClientBuilder.namespaceId(namespaceId);
        return this;
    }

    @Override
    public NacosUpdatingListenerBuilder groupName(String groupName) {
        nacosClientBuilder.groupName(groupName);
        return this;
    }

    @Override
    public NacosUpdatingListenerBuilder clusterName(String clusterName) {
        nacosClientBuilder.clusterName(clusterName);
        return this;
    }

    @Override
    public NacosUpdatingListenerBuilder app(String app) {
        nacosClientBuilder.app(app);
        return this;
    }

    @Override
    public NacosUpdatingListenerBuilder nacosApiVersion(String nacosApiVersion) {
        nacosClientBuilder.nacosApiVersion(nacosApiVersion);
        return this;
    }

    @Override
    public NacosUpdatingListenerBuilder authorization(String username, String password) {
        nacosClientBuilder.authorization(username, password);
        return this;
    }

    /**
     * Returns a newly-created {@link NacosUpdatingListener} that registers the {@link Server} to
     * Nacos when the {@link Server} starts.
     */
    public NacosUpdatingListener build() {
        return new NacosUpdatingListener(nacosClientBuilder.build(), endpoint);
    }
}
