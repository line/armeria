/*
 * Copyright 2026 LY Corporation
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

import static com.google.common.base.Preconditions.checkArgument;

import java.util.HashMap;
import java.util.Map;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap.StaticResources;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;

final class BootstrapSecrets {

    private final Map<String, SecretXdsResource> secrets = new HashMap<>();

    BootstrapSecrets(Bootstrap bootstrap) {
        if (bootstrap.hasStaticResources()) {
            final StaticResources staticResources = bootstrap.getStaticResources();
            for (Secret secret: staticResources.getSecretsList()) {
                checkArgument(!secrets.containsKey(secret.getName()),
                              "Static secret with name '%s' already registered", secret.getName());
                secrets.put(secret.getName(), new SecretXdsResource(secret));
            }
        }
    }

    SecretXdsResource get(String name) {
        final SecretXdsResource secretXdsResource = secrets.get(name);
        checkArgument(secretXdsResource != null, "Secret with name '%s' does not exist", name);
        return secretXdsResource;
    }
}
