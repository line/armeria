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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;

/**
 * A resource object for a {@link Listener}.
 */
@UnstableApi
public final class ListenerXdsResource implements XdsResource {

    private static final String HTTP_CONNECTION_MANAGER_TYPE_URL =
            "type.googleapis.com/" +
            "envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager";

    private final Listener listener;
    @Nullable
    private final HttpConnectionManager connectionManager;

    ListenerXdsResource(Listener listener) {
        this.listener = listener;

        final Any apiListener = listener.getApiListener().getApiListener();
        if (HTTP_CONNECTION_MANAGER_TYPE_URL.equals(apiListener.getTypeUrl())) {
            try {
                connectionManager = apiListener.unpack(HttpConnectionManager.class);
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalArgumentException(e);
            }
            checkArgument(connectionManager.hasRds() || connectionManager.hasRouteConfig(),
                          "connectionManager should have an RDS or RouteConfig");
        } else {
            connectionManager = null;
        }
    }

    @Override
    public XdsType type() {
        return XdsType.LISTENER;
    }

    @Override
    public Listener resource() {
        return listener;
    }

    @Nullable
    HttpConnectionManager connectionManager() {
        return connectionManager;
    }

    @Override
    public String name() {
        return listener.getName();
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final ListenerXdsResource resource = (ListenerXdsResource) object;
        return Objects.equal(listener, resource.listener);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(listener);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("listener", listener)
                          .toString();
    }
}
