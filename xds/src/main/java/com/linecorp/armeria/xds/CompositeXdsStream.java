/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.xds;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.core.v3.Node;
import io.netty.util.concurrent.EventExecutor;

final class CompositeXdsStream implements XdsStream {

    private final Map<XdsType, XdsStream> streamMap = new EnumMap<>(XdsType.class);

    CompositeXdsStream(GrpcClientBuilder clientBuilder, Node node, Backoff backoff,
                       EventExecutor eventLoop, XdsResponseHandler handler,
                       SubscriberStorage subscriberStorage) {
        for (XdsType type: XdsType.values()) {
            final SotwXdsStream stream = new SotwXdsStream(
                    SotwDiscoveryStub.basic(type, clientBuilder), node, backoff, eventLoop,
                    handler, subscriberStorage, EnumSet.of(type));
            streamMap.put(type, stream);
        }
    }

    @Override
    public void close() {
        streamMap.values().forEach(SafeCloseable::close);
    }

    @Override
    public void resourcesUpdated(XdsType type) {
        streamMap.get(type).resourcesUpdated(type);
    }
}
