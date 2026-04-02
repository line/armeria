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

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;

final class SecretStream extends RefCountedStream<SecretXdsResource> {

    @Nullable
    private final SdsSecretConfig sdsSecretConfig;
    @Nullable
    private final ConfigSource parentConfigSource;
    @Nullable
    private final Secret secret;
    private final SubscriptionContext context;

    SecretStream(SdsSecretConfig sdsSecretConfig, @Nullable ConfigSource parentConfigSource,
                 SubscriptionContext context) {
        this.sdsSecretConfig = sdsSecretConfig;
        this.parentConfigSource = parentConfigSource;
        secret = null;
        this.context = context;
    }

    SecretStream(Secret secret, SubscriptionContext context) {
        sdsSecretConfig = null;
        this.secret = secret;
        this.context = context;
        parentConfigSource = null;
    }

    @Override
    protected Subscription onStart(SnapshotWatcher<SecretXdsResource> watcher) {
        if (secret != null) {
            return SnapshotStream.just(new SecretXdsResource(secret)).subscribe(watcher);
        }
        assert sdsSecretConfig != null;
        final String name = sdsSecretConfig.getName();
        if (sdsSecretConfig.hasSdsConfig()) {
            final ConfigSource configSource = context.configSourceMapper().configSource(
                    sdsSecretConfig.getSdsConfig(), parentConfigSource);
            if (configSource == null) {
                final SnapshotStream<SecretXdsResource> errorStream = SnapshotStream.error(
                        new XdsResourceException(XdsType.SECRET, name, "config source not found"));
                return errorStream.subscribe(watcher);
            }
            final SnapshotStream<SecretXdsResource> resourceNodeAdapter =
                    new ResourceNodeAdapter<>(configSource, context, name, XdsType.SECRET);
            return resourceNodeAdapter.subscribe(watcher);
        }
        final SecretXdsResource resource = context.bootstrapSecrets().get(name);
        return SnapshotStream.just(resource).subscribe(watcher);
    }
}
