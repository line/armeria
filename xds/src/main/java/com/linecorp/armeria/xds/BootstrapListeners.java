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
package com.linecorp.armeria.xds;

import java.util.Map;

import com.google.common.collect.ImmutableMap;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap.StaticResources;
import io.envoyproxy.envoy.config.listener.v3.Listener;

final class BootstrapListeners {

    private final Map<String, ListenerXdsResource> listeners;

    BootstrapListeners(Bootstrap bootstrap) {
        final ImmutableMap.Builder<String, ListenerXdsResource> builder = ImmutableMap.builder();
        if (bootstrap.hasStaticResources()) {
            final StaticResources staticResources = bootstrap.getStaticResources();
            for (Listener listener: staticResources.getListenersList()) {
                final ListenerXdsResource listenerResource = ListenerResourceParser.INSTANCE.parse(listener);
                builder.put(listener.getName(), listenerResource);
            }
        }
        listeners = builder.build();
    }

    Map<String, ListenerXdsResource> staticListeners() {
        return listeners;
    }
}
