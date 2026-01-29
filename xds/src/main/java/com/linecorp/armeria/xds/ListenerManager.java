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

import java.util.HashMap;
import java.util.Map;

import com.google.common.base.Objects;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.xds.SnapshotStream.Subscription;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap.StaticResources;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.netty.util.concurrent.EventExecutor;

final class ListenerManager implements SafeCloseable {

    private final Map<String, ListenerStream> nodes = new HashMap<>();
    private final EventExecutor eventLoop;
    private boolean closed;
    private final Map<WatcherKey, Subscription> subscriptions = new HashMap<>();

    ListenerManager(EventExecutor eventLoop, Bootstrap bootstrap, SubscriptionContext subscriptionContext,
                    SnapshotWatcher<Object> defaultSnapshotWatcher) {
        this.eventLoop = eventLoop;
        // the manager is added as default for static resources so they are never unregistered
        // unless the bootstrap is closed
        initializeBootstrap(bootstrap, subscriptionContext, defaultSnapshotWatcher);
    }

    private void initializeBootstrap(Bootstrap bootstrap, SubscriptionContext bootstrapContext,
                                     SnapshotWatcher<Object> defaultSnapshotWatcher) {
        if (bootstrap.hasStaticResources()) {
            final StaticResources staticResources = bootstrap.getStaticResources();
            for (Listener listener: staticResources.getListenersList()) {
                register(listener, bootstrapContext, defaultSnapshotWatcher);
            }
        }
    }

    void register(Listener listener, SubscriptionContext context, SnapshotWatcher<Object> watcher) {
        checkArgument(!nodes.containsKey(listener.getName()),
                      "Static listener with name '%s' already registered", listener.getName());
        final ListenerStream node = new ListenerStream(new ListenerXdsResource(listener), context);
        nodes.put(listener.getName(), node);
        eventLoop.execute(() -> {
            try {
                final Subscription subscription = node.subscribe(watcher);
                subscriptions.put(new WatcherKey(watcher, listener.getName()), subscription);
            } catch (Throwable t) {
                watcher.onUpdate(null, XdsResourceException.maybeWrap(XdsType.LISTENER, listener.getName(), t));
            }
        });
    }

    void register(String name, SubscriptionContext context, SnapshotWatcher<ListenerSnapshot> watcher) {
        if (closed) {
            return;
        }
        final ListenerStream node = nodes.computeIfAbsent(name, ignored -> {
            // on-demand lds if not already registered
            return new ListenerStream(name, context);
        });
        final Subscription subscription = node.subscribe(watcher);
        subscriptions.put(new WatcherKey(watcher, name), subscription);
    }

    void unregister(String name, SnapshotWatcher<ListenerSnapshot> watcher) {
        if (closed) {
            return;
        }
        final WatcherKey key = new WatcherKey(watcher, name);
        final Subscription subscription = subscriptions.remove(key);
        if (subscription != null) {
            subscription.close();
            final ListenerStream node = nodes.get(name);
            if (node != null && !node.hasWatchers()) {
                nodes.remove(name);
            }
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
        for (Subscription subscription: subscriptions.values()) {
            subscription.close();
        }
    }

    static final class WatcherKey {
        private final SnapshotWatcher<?> watcher;
        private final String name;

        WatcherKey(SnapshotWatcher<?> watcher, String name) {
            this.watcher = watcher;
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            final WatcherKey that = (WatcherKey) o;
            return Objects.equal(watcher, that.watcher) &&
                   Objects.equal(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(watcher, name);
        }
    }
}
