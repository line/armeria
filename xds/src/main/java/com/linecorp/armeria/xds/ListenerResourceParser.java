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

import com.google.protobuf.Message;

import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.listener.v3.ListenerOrBuilder;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;

final class ListenerResourceParser extends ResourceParser {

    static final ListenerResourceParser INSTANCE = new ListenerResourceParser();

    private ListenerResourceParser() {}

    @Override
    ListenerResourceHolder parse(Message message) {
        if (!(message instanceof Listener)) {
            throw new IllegalArgumentException("message not type of Listener");
        }
        final ListenerResourceHolder holder = new ListenerResourceHolder((Listener) message);
        final HttpConnectionManager connectionManager = holder.connectionManager();
        if (connectionManager != null) {
            if (connectionManager.hasRds()) {
                final Rds rds = connectionManager.getRds();
                XdsConverterUtil.validateConfigSource(rds.getConfigSource());
            }
        }
        return holder;
    }

    @Override
    String name(Message message) {
        if (!(message instanceof Listener)) {
            throw new IllegalArgumentException("message not type of Listener");
        }
        return ((ListenerOrBuilder) message).getName();
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
