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

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.filter.XdsHttpFilter;

import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

final class ListenerResourceParser extends ResourceParser<Listener, ListenerXdsResource> {

    static final ListenerResourceParser INSTANCE = new ListenerResourceParser();

    private ListenerResourceParser() {}

    @Nullable
    private static HttpConnectionManager unpackConnectionManager(Listener listener,
                                                         XdsExtensionRegistry registry) {
        if (listener.getApiListener().hasApiListener()) {
            final Any apiListener = listener.getApiListener().getApiListener();
            final HttpConnectionManagerFactory factory =
                    registry.queryByTypeUrl(apiListener.getTypeUrl(),
                                            HttpConnectionManagerFactory.class);
            assert factory != null;
            return factory.create(apiListener, registry.validator());
        }
        return null;
    }

    private static List<XdsHttpFilter> resolveDownstreamFilters(
            @Nullable HttpConnectionManager connectionManager,
            XdsExtensionRegistry registry) {
        if (connectionManager == null) {
            return ImmutableList.of();
        }
        final List<HttpFilter> httpFilters = connectionManager.getHttpFiltersList();
        final ImmutableList.Builder<XdsHttpFilter> builder = ImmutableList.builder();
        for (HttpFilter httpFilter : httpFilters) {
            final XdsHttpFilter instance = FilterUtil.resolveInstance(registry, httpFilter, null);
            if (instance != null) {
                builder.add(instance);
            }
        }
        return builder.build();
    }

    @Override
    ListenerXdsResource parse(Listener message, XdsExtensionRegistry registry, String version) {
        final HttpConnectionManager connectionManager =
                unpackConnectionManager(message, registry);
        final List<XdsHttpFilter> downstreamFilters =
                resolveDownstreamFilters(connectionManager, registry);
        return new ListenerXdsResource(message, connectionManager, downstreamFilters, version);
    }

    @Override
    String name(Listener message) {
        return message.getName();
    }

    @Override
    Class<Listener> clazz() {
        return Listener.class;
    }

    @Override
    boolean isFullStateOfTheWorld() {
        return true;
    }

    @Override
    XdsType type() {
        return XdsType.LISTENER;
    }
}
