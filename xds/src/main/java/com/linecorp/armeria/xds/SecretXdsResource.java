/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.xds;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;

/**
 * An xDS resource wrapper for {@link Secret} resources.
 * Secrets contain TLS certificates, private keys, and validation contexts
 * used for securing transport sockets in xDS configurations.
 */
@UnstableApi
public final class SecretXdsResource implements XdsResource {

    private final Secret secret;
    private final String version;
    private final long revision;

    SecretXdsResource(Secret secret) {
        this(secret, "", 0);
    }

    SecretXdsResource(Secret secret, String version, long revision) {
        XdsValidatorIndexRegistry.assertValid(secret);
        this.secret = secret;
        this.version = version;
        this.revision = revision;
    }

    @Override
    public XdsType type() {
        return XdsType.SECRET;
    }

    @Override
    public Secret resource() {
        return secret;
    }

    @Override
    public String name() {
        return secret.getName();
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public long revision() {
        return revision;
    }
}
