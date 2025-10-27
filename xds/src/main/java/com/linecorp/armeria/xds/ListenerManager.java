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
import static com.linecorp.armeria.xds.ResourceNodeType.STATIC;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap.StaticResources;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.netty.util.concurrent.EventExecutor;

final class ListenerManager implements SafeCloseable, SnapshotWatcher<ListenerSnapshot> {

    private final Map<String, ListenerResourceNode> nodes = new HashMap<>();
    private final EventExecutor eventLoop;
    private boolean closed;
    private final List<SnapshotWatcher<? super ListenerSnapshot>> watchers;

    ListenerManager(EventExecutor eventLoop, SnapshotWatcher<Object> defaultSnapshotWatcher) {
        this.eventLoop = eventLoop;
        watchers = ImmutableList.of(this, defaultSnapshotWatcher);
    }

    void initializeBootstrap(Bootstrap bootstrap, BootstrapContext bootstrapContext) {
        if (bootstrap.hasStaticResources()) {
            final StaticResources staticResources = bootstrap.getStaticResources();
            for (Listener listener: staticResources.getListenersList()) {
                register(listener, bootstrapContext, watchers, "", 0);
            }
        }
    }

    void register(Listener listener, BootstrapContext context,
                  Iterable<SnapshotWatcher<? super ListenerSnapshot>> watchers, String version,
                  long revision) {
        checkArgument(!nodes.containsKey(listener.getName()),
                      "Static listener with name '%s' already registered", listener.getName());
        final ListenerResourceNode node = new ListenerResourceNode(null, listener.getName(), context, STATIC);
        for (SnapshotWatcher<? super ListenerSnapshot> watcher: watchers) {
            node.addWatcher(watcher);
        }
        nodes.put(listener.getName(), node);

        final ListenerXdsResource listenerResource =
                ListenerResourceParser.INSTANCE.parse(listener, version, revision);
        eventLoop.execute(() -> node.onChanged(listenerResource));
    }

    void register(String name, BootstrapContext context, SnapshotWatcher<ListenerSnapshot> watcher) {
        if (closed) {
            return;
        }
        final ListenerResourceNode node = nodes.computeIfAbsent(name, ignored -> {
            // on-demand lds if not already registered
            final ConfigSource configSource = context.configSourceMapper().ldsConfigSource(name);
            final ListenerResourceNode dynamicNode =
                    new ListenerResourceNode(configSource, name, context,
                                             watcher, ResourceNodeType.DYNAMIC);
            context.subscribe(dynamicNode);
            return dynamicNode;
        });
        node.addWatcher(watcher);
    }

    void unregister(String name, SnapshotWatcher<ListenerSnapshot> watcher) {
        if (closed) {
            return;
        }
        final ListenerResourceNode node = nodes.get(name);
        if (node == null) {
            return;
        }
        node.removeWatcher(watcher);
        if (!node.hasWatchers()) {
            node.close();
            nodes.remove(name);
        }
    }

    @Override
    public void close() {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(this::close);
            return;
        }
        if (closed) {
            return;
        }
        closed = true;
        for (ListenerResourceNode node : nodes.values()) {
            node.close();
        }
    }

    @Override
    public void snapshotUpdated(ListenerSnapshot newSnapshot) {
        // noop
    }
}
