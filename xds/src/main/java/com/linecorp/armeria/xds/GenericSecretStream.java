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

import java.util.Optional;

import com.google.protobuf.ByteString;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.stream.RefCountedStream;
import com.linecorp.armeria.xds.stream.SnapshotStream;
import com.linecorp.armeria.xds.stream.Subscription;

import io.envoyproxy.envoy.config.core.v3.WatchedDirectory;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.GenericSecret;

final class GenericSecretStream extends RefCountedStream<GenericSecretSnapshot> {

    private final SubscriptionContext context;
    private final SecretXdsResource resource;

    GenericSecretStream(SubscriptionContext context, SecretXdsResource resource) {
        this.context = context;
        this.resource = resource;
    }

    @Override
    protected Subscription onStart(SnapshotWatcher<GenericSecretSnapshot> watcher) {
        final GenericSecret genericSecret = resource.resource().getGenericSecret();
        if (!genericSecret.hasSecret()) {
            final SnapshotStream<GenericSecretSnapshot> errorStream = SnapshotStream.error(
                    new XdsResourceException(XdsType.SECRET, resource.name(),
                                             "GenericSecret does not contain a secret data source"));
            return errorStream.subscribe(watcher);
        }
        final DataSourceStream dataSourceStream =
                new DataSourceStream(genericSecret.getSecret(),
                                     WatchedDirectory.getDefaultInstance(), context);
        final SnapshotStream<GenericSecretSnapshot> stream =
                dataSourceStream.map(optBytes -> {
                    final String credential = toCredentialValue(optBytes);
                    return new GenericSecretSnapshot(genericSecret, credential);
                });
        return stream.subscribe(watcher);
    }

    @Nullable
    private static String toCredentialValue(Optional<ByteString> optBytes) {
        if (!optBytes.isPresent()) {
            return null;
        }
        final String value = optBytes.get().toStringUtf8();
        return value.isEmpty() ? null : value;
    }
}
