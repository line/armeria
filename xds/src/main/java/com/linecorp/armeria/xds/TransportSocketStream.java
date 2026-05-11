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

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.TransportSocket;

final class TransportSocketStream extends RefCountedStream<TransportSocketSnapshot> {

    private final SubscriptionContext context;
    @Nullable
    private final ConfigSource configSource;
    private final TransportSocket transportSocket;

    TransportSocketStream(SubscriptionContext context, @Nullable ConfigSource configSource,
                          TransportSocket transportSocket) {
        this.context = context;
        this.configSource = configSource;
        this.transportSocket = transportSocket;
    }

    @Override
    protected Subscription onStart(SnapshotWatcher<TransportSocketSnapshot> watcher) {
        if (transportSocket.equals(TransportSocket.getDefaultInstance())) {
            return SnapshotStream.just(new TransportSocketSnapshot(transportSocket))
                                 .subscribe(watcher);
        }
        final TransportSocketFactory factory = context.extensionRegistry().query(
                transportSocket.getTypedConfig(), transportSocket.getName(),
                TransportSocketFactory.class);
        if (factory == null) {
            throw new IllegalArgumentException(
                    "No TransportSocketFactory registered for transport socket: " +
                    transportSocket.getName());
        }
        return factory.create(context, configSource, transportSocket).subscribe(watcher);
    }
}
