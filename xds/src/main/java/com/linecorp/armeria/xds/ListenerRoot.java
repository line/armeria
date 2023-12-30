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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

/**
 * A root node representing a {@link Listener}.
 * Users may query the latest value of this resource or add a watcher to be notified of changes.
 * Note that it is important to close this resource to avoid leaking connections to the control plane server.
 */
public final class ListenerRoot extends AbstractNode<ListenerResourceHolder> implements SafeCloseable {

    private final String resourceName;
    @Nullable
    private final ResourceNode<?> node;

    ListenerRoot(WatchersStorage watchersStorage, String resourceName, boolean autoSubscribe) {
        super(watchersStorage);
        this.resourceName = resourceName;
        if (autoSubscribe) {
            node = watchersStorage().subscribe(XdsType.LISTENER, resourceName);
        } else {
            node = null;
        }
        watchersStorage().addWatcher(XdsType.LISTENER, resourceName, this);
    }

    /**
     * Returns a node representation of the {@link RouteConfiguration} contained by this listener.
     */
    public RouteNode routeNode() {
        return new RouteNode(watchersStorage(), this);
    }

    @Override
    public void close() {
        if (node != null) {
            watchersStorage().unsubscribe(null, XdsType.LISTENER, resourceName, node);
        }
        watchersStorage().removeWatcher(XdsType.LISTENER, resourceName, this);
    }
}
