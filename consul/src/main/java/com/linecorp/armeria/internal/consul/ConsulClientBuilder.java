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
package com.linecorp.armeria.internal.consul;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.consul.ConsulConfigSetters;

public final class ConsulClientBuilder implements ConsulConfigSetters {
    public static final String DEFAULT_CONSUL_API_VERSION = "v1";
    private static final Pattern CONSUL_API_VERSION_PATTERN = Pattern.compile("^v[0-9][-._a-zA-Z0-9]*$");

    private final URI consulUri;
    private String consulApiVersion = DEFAULT_CONSUL_API_VERSION;
    @Nullable
    private String consulToken;

    ConsulClientBuilder(URI consulUri) {
        this.consulUri = requireNonNull(consulUri, "consulUri");
    }

    @Override
    public ConsulClientBuilder consulApiVersion(String consulApiVersion) {
        requireNonNull(consulApiVersion, "consulApiVersion");
        checkArgument(CONSUL_API_VERSION_PATTERN.matcher(consulApiVersion).matches(),
                      "consulApiVersion: %s (expected: a version string that starts with 'v', e.g. 'v1')",
                      consulApiVersion);
        this.consulApiVersion = consulApiVersion;
        return this;
    }

    @Override
    public ConsulClientBuilder consulToken(String consulToken) {
        requireNonNull(consulToken, "consulToken");
        checkArgument(!consulToken.isEmpty(), "consulToken can't be empty");
        this.consulToken = consulToken;
        return this;
    }

    public ConsulClient build() {
        final URI uri;
        try {
            uri = new URI(consulUri.getScheme(), null, consulUri.getHost(), consulUri.getPort(),
                          '/' + consulApiVersion, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
        return new ConsulClient(uri, consulToken);
    }
}
