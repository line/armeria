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
import static com.linecorp.armeria.xds.AbstractRoot.safeRunnable;
import static com.linecorp.armeria.xds.XdsResourceException.maybeWrap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final List<Subscription> subscriptions = new ArrayList<>();

    ListenerManager(EventExecutor eventLoop, Bootstrap bootstrap, SubscriptionContext subscriptionContext,
                    SnapshotWatcher<Object> defaultSnapshotWatcher) {
        this.eventLoop = eventLoop;
        // the manager is added as default for static resources so they are never unregistered
        // unless the bootstrap is closed
        initializeBootstrap(bootstrap, subscriptionContext, defaultSnapshotWatcher);
    }

    private void initializeBootstrap(Bootstrap bootstrap, SubscriptionContext bootstrapContext,
                                     SnapshotWatcher<Object> watcher) {
        if (bootstrap.hasStaticResources()) {
            final StaticResources staticResources = bootstrap.getStaticResources();
            for (Listener listener: staticResources.getListenersList()) {
                register(listener, bootstrapContext, watcher);
            }
        }
    }

    void register(Listener listener, SubscriptionContext context, SnapshotWatcher<Object> watcher) {
        checkArgument(!nodes.containsKey(listener.getName()),
                      "Static listener with name '%s' already registered", listener.getName());
        final ListenerStream node = new ListenerStream(new ListenerXdsResource(listener), context);
        nodes.put(listener.getName(), node);
        eventLoop.execute(safeRunnable(() -> {
            final Subscription subscription = node.subscribe(watcher);
            subscriptions.add(subscription);
        }, t -> watcher.onUpdate(null, maybeWrap(XdsType.LISTENER, listener.getName(), t))));
    }

    Subscription register(String name, SubscriptionContext context, SnapshotWatcher<ListenerSnapshot> watcher) {
        if (closed) {
            return Subscription.noop();
        }
        final ListenerStream node = nodes.computeIfAbsent(name, ignored -> {
            // on-demand lds if not already registered
            return new ListenerStream(name, context);
        });
        final Subscription subscription = node.subscribe(watcher);
        return () -> {
            subscription.close();
            if (!node.hasWatchers()) {
                nodes.remove(name);
            }
        };
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
        for (Subscription subscription: subscriptions) {
            subscription.close();
        }
    }
}
