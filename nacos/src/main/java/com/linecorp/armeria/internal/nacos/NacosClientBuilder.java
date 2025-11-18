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
package com.linecorp.armeria.internal.nacos;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.regex.Pattern;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.nacos.NacosConfigSetters;

public final class NacosClientBuilder implements NacosConfigSetters<NacosClientBuilder> {

    public static final String DEFAULT_NACOS_API_VERSION = "v2";
    private static final Pattern NACOS_API_VERSION_PATTERN = Pattern.compile("^v[0-9][-._a-zA-Z0-9]*$");

    private final URI nacosUri;
    private final String serviceName;

    private String nacosApiVersion = DEFAULT_NACOS_API_VERSION;

    @Nullable
    private String username;

    @Nullable
    private String password;

    @Nullable
    private String namespaceId;

    @Nullable
    private String groupName;

    @Nullable
    private String clusterName;

    @Nullable
    private Boolean healthyOnly;

    @Nullable
    private String app;

    NacosClientBuilder(URI nacosUri, String serviceName) {
        this.nacosUri = requireNonNull(nacosUri, "nacosUri");
        this.serviceName = requireNonNull(serviceName, "serviceName");
    }

    @Override
    public NacosClientBuilder namespaceId(String namespaceId) {
        this.namespaceId = requireNonNull(namespaceId, "namespaceId");
        return this;
    }

    @Override
    public NacosClientBuilder groupName(String groupName) {
        this.groupName = requireNonNull(groupName, "groupName");
        return this;
    }

    @Override
    public NacosClientBuilder clusterName(String clusterName) {
        this.clusterName = requireNonNull(clusterName, "clusterName");
        return this;
    }

    @Override
    public NacosClientBuilder app(String app) {
        this.app = requireNonNull(app, "app");
        return this;
    }

    @Override
    public NacosClientBuilder nacosApiVersion(String nacosApiVersion) {
        requireNonNull(nacosApiVersion, "nacosApiVersion");
        checkArgument(NACOS_API_VERSION_PATTERN.matcher(nacosApiVersion).matches(),
                      "nacosApiVersion: %s (expected: a version string that starts with 'v', e.g. 'v1')",
                      nacosApiVersion);
        this.nacosApiVersion = nacosApiVersion;
        return this;
    }

    @Override
    public NacosClientBuilder authorization(String username, String password) {
        requireNonNull(username, "username");
        requireNonNull(password, "password");

        this.username = username;
        this.password = password;

        return this;
    }

    public NacosClientBuilder healthyOnly(boolean healthyOnly) {
        this.healthyOnly = healthyOnly;
        return this;
    }

    public NacosClient build() {
        return new NacosClient(nacosUri, nacosApiVersion, username, password, serviceName, namespaceId,
                               groupName, clusterName, healthyOnly, app);
    }
}
