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
package com.linecorp.armeria.server.nacos;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.URI;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
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
 *                                                         .build();
 * ServerBuilder sb = Server.builder();
 * sb.serverListener(listener);
 * }</pre>
 */
@UnstableApi
public final class NacosUpdatingListenerBuilder implements NacosConfigSetters {

    private final String serviceName;

    @Nullable
    private Endpoint endpoint;

    @Nullable
    private String namespaceId;

    @Nullable
    private String groupName;

    @Nullable
    private String clusterName;

    @Nullable
    private String app;

    private final NacosClientBuilder nacosClientBuilder;

    /**
     * Creates a {@link NacosUpdatingListenerBuilder} with a service name.
     *
     * @param nacosUri the URI of Nacos API service
     * @param serviceName the service name to register
     */
    NacosUpdatingListenerBuilder(URI nacosUri, String serviceName) {
        this.serviceName = requireNonNull(serviceName, "serviceName");
        checkArgument(!this.serviceName.isEmpty(), "serviceName can't be empty");
        nacosClientBuilder = NacosClient.builder(nacosUri);
    }

    /**
     * Sets the {@link Endpoint} to register. If not set, the current host name is used by default.
     *
     * @param endpoint the {@link Endpoint} to register
     */
    public NacosUpdatingListenerBuilder endpoint(Endpoint endpoint) {
        this.endpoint = requireNonNull(endpoint, "endpoint");
        return this;
    }

    /**
     * Sets the namespace ID to register the instance.
     *
     * @param namespaceId the namespace ID to register.
     */
    public NacosUpdatingListenerBuilder namespaceId(String namespaceId) {
        this.namespaceId = requireNonNull(namespaceId, "namespaceId");
        return this;
    }

    /**
     * Sets the group name of the instance.
     *
     * @param groupName the group name of the instance.
     */
    public NacosUpdatingListenerBuilder groupName(String groupName) {
        this.groupName = requireNonNull(groupName, "groupName");
        return this;
    }

    /**
     * Sets the cluster name of the instance.
     *
     * @param clusterName the cluster name of the instance.
     */
    public NacosUpdatingListenerBuilder clusterName(String clusterName) {
        this.clusterName = requireNonNull(clusterName, "clusterName");
        return this;
    }

    /**
     * Sets the app name of the instance.
     *
     * @param app app name of the instance.
     */
    public NacosUpdatingListenerBuilder app(String app) {
        this.app = requireNonNull(app, "app");
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
        return new NacosUpdatingListener(nacosClientBuilder.build(), serviceName, endpoint, namespaceId,
                                         groupName, clusterName, app);
    }
}
