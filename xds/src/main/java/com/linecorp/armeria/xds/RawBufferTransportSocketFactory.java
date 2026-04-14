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

import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.TransportSocket;

final class RawBufferTransportSocketFactory implements TransportSocketFactory {

    static final RawBufferTransportSocketFactory INSTANCE = new RawBufferTransportSocketFactory();
    private static final String NAME = "envoy.transport_sockets.raw_buffer";
    private static final String TYPE_URL =
            "type.googleapis.com/envoy.extensions.transport_sockets.raw_buffer.v3.RawBuffer";

    private RawBufferTransportSocketFactory() {}

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> typeUrls() {
        return ImmutableList.of(TYPE_URL);
    }

    @Override
    public SnapshotStream<TransportSocketSnapshot> create(
            SubscriptionContext context, @Nullable ConfigSource configSource,
            TransportSocket transportSocket) {
        return SnapshotStream.just(new TransportSocketSnapshot(transportSocket));
    }
}
