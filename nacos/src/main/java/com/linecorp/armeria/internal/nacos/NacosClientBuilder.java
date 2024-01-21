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
package com.linecorp.armeria.internal.nacos;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.nacos.NacosConfigSetters;

public final class NacosClientBuilder implements NacosConfigSetters {
    public static final String DEFAULT_NACOS_API_VERSION = "v2";
    private static final Pattern NACOS_API_VERSION_PATTERN = Pattern.compile("^v[0-9][-._a-zA-Z0-9]*$");
    private final URI nacosUri;

    private String nacosApiVersion = DEFAULT_NACOS_API_VERSION;

    @Nullable
    private String username;

    @Nullable
    private String password;

    NacosClientBuilder(URI nacosUri) {
        this.nacosUri = requireNonNull(nacosUri, "nacosUri");
    }

    @Override
    public NacosConfigSetters nacosApiVersion(String nacosApiVersion) {
        this.nacosApiVersion = requireNonNull(nacosApiVersion, "nacosApiVersion");
        checkArgument(NACOS_API_VERSION_PATTERN.matcher(nacosApiVersion).matches(),
                      "nacosApiVersion: %s (expected: a version string that starts with 'v', e.g. 'v1')",
                      nacosApiVersion);

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

    public NacosClient build() {
        final URI uri;
        try {
            uri = new URI(nacosUri.getScheme(), null, nacosUri.getHost(), nacosUri.getPort(),
                    "/nacos", null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }

        return new NacosClient(uri, nacosApiVersion, username, password);
    }
}
