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

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.Route.ActionCase;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;

/**
 * Say that we register a listener via {@link XdsBootstrap#addListenerWatcher(String, ResourceWatcher)}.
 * The user will only receive updates for the listener, although he/she may be interested
 * in the eventually fetched endpoints.
 * This watcher aggregates resources such that all ensuing resources may also be observed.
 * As of now, the start of the chain is updated first, and then the remaining chain is updated.
 * For instance, if a listener is changed the order of update will be as follows:
 * <p>Listener -> Route -> Cluster -> Endpoint</p>
 */
final class AggregatingWatcher implements ResourceWatcher<ResourceHolder<?>>, SafeCloseable {

    private final XdsBootstrap xdsBootstrap;
    Map<XdsType, Deque<SafeCloseable>> closeables = new HashMap<>();

    private final Map<String, Listener> listenerMap = new HashMap<>();
    private final Map<String, RouteConfiguration> routeMap = new HashMap<>();
    private final Map<String, Cluster> clusterMap = new HashMap<>();
    private final Map<String, ClusterLoadAssignment> endpointMap = new HashMap<>();
    private final AggregatingWatcherListener listener;
    private final SafeCloseable closeable;

    AggregatingWatcher(XdsBootstrap xdsBootstrap, XdsType xdsType, String resourceName,
                       AggregatingWatcherListener listener) {
        this.xdsBootstrap = xdsBootstrap;
        for (XdsType type: XdsType.values()) {
            closeables.put(type, new ArrayDeque<>());
        }
        this.listener = listener;
        switch (xdsType) {
            case LISTENER:
                closeable = xdsBootstrap.addListenerWatcher(resourceName, this::onChanged);
                break;
            case ROUTE:
                closeable = xdsBootstrap.addRouteWatcher(resourceName, this::onChanged);
                break;
            case CLUSTER:
                closeable = xdsBootstrap.addClusterWatcher(resourceName, this::onChanged);
                break;
            case ENDPOINT:
                closeable = xdsBootstrap.addEndpointWatcher(resourceName, this::onChanged);
                break;
            default:
                throw new Error("Shouldn't reach here");
        }
    }

    @Override
    public void onResourceDoesNotExist(XdsType type, String resourceName) {
        cleanupPreviousWatchers(type);
        notify(type);
    }

    @Override
    public void onChanged(ResourceHolder<?> update) {
        cleanupPreviousWatchers(update.type());
        final Deque<SafeCloseable> safeCloseables = closeables.get(update.type());
        switch (update.type()) {
            case LISTENER:
                onListenerChanged((ListenerResourceHolder) update, safeCloseables);
                break;
            case ROUTE:
                onRouteChanged((RouteResourceHolder) update, safeCloseables);
                break;
            case CLUSTER:
                onClusterChanged((ClusterResourceHolder) update, safeCloseables);
                break;
            case ENDPOINT:
                onEndpointChanged((EndpointResourceHolder) update);
                break;
            default:
                throw new Error("Shouldn't reach here");
        }
    }

    void onListenerChanged(ListenerResourceHolder holder, Deque<SafeCloseable> safeCloseables) {
        final HttpConnectionManager connectionManager = holder.connectionManager();
        if (connectionManager == null) {
            return;
        }
        final Listener listener = holder.data();
        final String routeName;
        if (connectionManager.hasRouteConfig()) {
            routeName = connectionManager.getRouteConfig().getName();
        } else if (connectionManager.hasRds()) {
            routeName = connectionManager.getRds().getRouteConfigName();
        } else {
            throw new IllegalArgumentException("A connection manager should have a RouteConfig or RDS.");
        }
        listenerMap.put(listener.getName(), listener);
        notify(holder.type());
        safeCloseables.add(xdsBootstrap.addRouteWatcher(routeName, this::onChanged));
    }

    void onRouteChanged(RouteResourceHolder holder, Deque<SafeCloseable> safeCloseables) {
        final RouteConfiguration routeConfiguration = holder.data();
        routeMap.put(routeConfiguration.getName(), routeConfiguration);
        notify(holder.type());
        for (Route route: holder.routes()) {
            if (route.getActionCase() != ActionCase.ROUTE) {
                continue;
            }
            final RouteAction routeAction = route.getRoute();
            final String cluster = routeAction.getCluster();
            safeCloseables.add(xdsBootstrap.addClusterWatcher(cluster, this::onChanged));
        }
    }

    void onClusterChanged(ClusterResourceHolder holder, Deque<SafeCloseable> safeCloseables) {
        final Cluster cluster = holder.data();
        clusterMap.put(cluster.getName(), cluster);
        notify(holder.type());
        safeCloseables.add(xdsBootstrap.addEndpointWatcher(cluster.getName(), this::onChanged));
    }

    void onEndpointChanged(EndpointResourceHolder holder) {
        final ClusterLoadAssignment loadAssignment = holder.data();
        endpointMap.put(loadAssignment.getClusterName(), loadAssignment);
        notify(holder.type());
    }

    void notify(XdsType type) {
        switch (type) {
            case LISTENER:
                listener.onListenerUpdate(Collections.unmodifiableMap(listenerMap));
                break;
            case ROUTE:
                listener.onRouteUpdate(Collections.unmodifiableMap(routeMap));
                break;
            case CLUSTER:
                listener.onClusterUpdate(Collections.unmodifiableMap(clusterMap));
                break;
            case ENDPOINT:
                listener.onEndpointUpdate(Collections.unmodifiableMap(endpointMap));
                break;
            default:
                throw new Error("Shouldn't reach here");
        }
    }

    void cleanupPreviousWatchers(XdsType type) {
        final Deque<SafeCloseable> safeCloseables = closeables.get(type);
        while (!safeCloseables.isEmpty()) {
            safeCloseables.poll().close();
        }
    }

    @Override
    public void close() {
        closeable.close();
    }
}
