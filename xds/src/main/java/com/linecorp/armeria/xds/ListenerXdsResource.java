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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Any;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.listener.v3.Filter;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

/**
 * A resource object for a {@link Listener}.
 */
@UnstableApi
public final class ListenerXdsResource extends AbstractXdsResource {

    private static final Logger logger = LoggerFactory.getLogger(ListenerXdsResource.class);

    private static final String HTTP_CONNECTION_MANAGER_TYPE_URL =
            "type.googleapis.com/" +
            "envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager";
    private static final String HTTP_CONNECTION_MANAGER_FILTER_NAME =
            "envoy.filters.network.http_connection_manager";
    private static final String ROUTER_TYPE_URL =
            "type.googleapis.com/envoy.extensions.filters.http.router.v3.Router";

    private final Listener listener;
    @Nullable
    private final HttpConnectionManager connectionManager;
    @Nullable
    private final Router router;

    ListenerXdsResource(Listener listener) {
        this(listener, "");
    }

    ListenerXdsResource(Listener listener, String version) {
        this(listener, version, 0);
    }

    private ListenerXdsResource(Listener listener, String version, long revision) {
        super(version, revision);
        XdsValidatorIndexRegistry.assertValid(listener);
        this.listener = listener;

        connectionManager = findHcm(listener);
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
     * The {@link HttpConnectionManager} derived from the {@link Listener} intended for client-side.
     * The lookup order is: api_listener, filter chains, default filter chain.
     */
    @Nullable
    public HttpConnectionManager connectionManager() {
        return connectionManager;
    }

    @Override
    public String name() {
        return listener.getName();
    }

    @Override
    ListenerXdsResource withRevision(long revision) {
        if (revision == revision()) {
            return this;
        }
        return new ListenerXdsResource(listener, version(), revision);
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
        return XdsValidatorIndexRegistry.unpack(lastHttpFilter.getTypedConfig(), Router.class);
    }

    @Nullable
    private static HttpConnectionManager findHcm(Listener listener) {
        // 1. api_listener
        if (listener.getApiListener().hasApiListener()) {
            final Any apiListener = listener.getApiListener().getApiListener();
            return XdsValidatorIndexRegistry.unpack(apiListener, HttpConnectionManager.class);
        }
        logger.warn("No api_listener set for listener {}; falling back to filter chains.", listener.getName());

        // 2. filter chains
        for (FilterChain fc : listener.getFilterChainsList()) {
            final HttpConnectionManager hcm = findHcmInFilterChain(fc);
            if (hcm != null) {
                return hcm;
            }
        }
        // 3. default filter chain
        if (listener.hasDefaultFilterChain()) {
            return findHcmInFilterChain(listener.getDefaultFilterChain());
        }
        return null;
    }

    @Nullable
    private static HttpConnectionManager findHcmInFilterChain(FilterChain filterChain) {
        final List<Filter> filters = filterChain.getFiltersList();
        if (filters.isEmpty()) {
            return null;
        }
        // HCM is a terminal network filter and should be the last in the chain.
        final Filter last = filters.get(filters.size() - 1);
        if (HTTP_CONNECTION_MANAGER_FILTER_NAME.equals(last.getName()) &&
            last.hasTypedConfig() &&
            HTTP_CONNECTION_MANAGER_TYPE_URL.equals(last.getTypedConfig().getTypeUrl())) {
            return XdsValidatorIndexRegistry.unpack(
                    last.getTypedConfig(), HttpConnectionManager.class);
        }
        return null;
    }
}
