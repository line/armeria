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

import static com.google.common.base.Preconditions.checkArgument;

import java.util.List;

import com.google.protobuf.Any;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

/**
 * A resource object for a {@link Listener}.
 */
@UnstableApi
public final class ListenerXdsResource extends AbstractXdsResource {

    private static final String HTTP_CONNECTION_MANAGER_TYPE_URL =
            "type.googleapis.com/" +
            "envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager";
    private static final String ROUTER_TYPE_URL =
            "type.googleapis.com/envoy.extensions.filters.http.router.v3.Router";

    private final Listener listener;
    @Nullable
    private final HttpConnectionManager connectionManager;
    @Nullable
    private final Router router;

    ListenerXdsResource(Listener listener, String version, long revision) {
        super(version, revision);
        XdsValidatorIndex.of().assertValid(listener);
        this.listener = listener;

        if (listener.getApiListener().hasApiListener()) {
            final Any apiListener = listener.getApiListener().getApiListener();
            if (HTTP_CONNECTION_MANAGER_TYPE_URL.equals(apiListener.getTypeUrl())) {
                connectionManager = XdsValidatorIndex.of().unpack(apiListener, HttpConnectionManager.class);
            } else {
                throw new IllegalArgumentException("Unsupported api listener: " + apiListener);
            }
        } else {
            connectionManager = null;
        }

        router = router(connectionManager);
    }

    @Override
    public XdsType type() {
        return XdsType.LISTENER;
    }

    @Override
    public Listener resource() {
        return listener;
    }

    /**
     * The {@link HttpConnectionManager} contained in the {@link Listener#getListenerFiltersList()}.
     */
    @Nullable
    public HttpConnectionManager connectionManager() {
        return connectionManager;
    }

    @Override
    public String name() {
        return listener.getName();
    }

    /**
     * The {@link Router} contained in the {@link Listener}.
     */
    @Nullable
    public Router router() {
        return router;
    }

    @Nullable
    private static Router router(@Nullable HttpConnectionManager connectionManager) {
        if (connectionManager == null) {
            return null;
        }
        final List<HttpFilter> httpFilters = connectionManager.getHttpFiltersList();
        if (httpFilters.isEmpty()) {
            return null;
        }
        final HttpFilter lastHttpFilter = httpFilters.get(httpFilters.size() - 1);
        if (!ROUTER_TYPE_URL.equals(lastHttpFilter.getTypedConfig().getTypeUrl())) {
            // the router should be the last/terminal filter
            return null;
        }
        checkArgument(lastHttpFilter.hasTypedConfig(), "Only typedConfig is supported for 'Router'.");
        return XdsValidatorIndex.of().unpack(lastHttpFilter.getTypedConfig(), Router.class);
    }
}
