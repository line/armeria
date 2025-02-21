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

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.listener.v3.Listener;

/**
 * A root node representing a {@link Listener}.
 * Users may query the latest value of this resource or add a watcher to be notified of changes.
 * Note that it is important to close this resource to avoid leaking connections to the control plane server.
 */
@UnstableApi
public final class ListenerRoot extends AbstractRoot<ListenerSnapshot> {

    private final ListenerResourceNode node;
    final SubscriptionContext context;

    ListenerRoot(SubscriptionContext context,
                 String resourceName, BootstrapListeners bootstrapListeners) {
        super(context.eventLoop());
        this.context = context;
        final ListenerXdsResource listenerXdsResource = bootstrapListeners.staticListeners().get(resourceName);
        if (listenerXdsResource != null) {
            node = new ListenerResourceNode(null, resourceName, context,
                                            this, ResourceNodeType.STATIC);
            node.onChanged(listenerXdsResource);
        } else {
            final ConfigSource configSource = context.configSourceMapper()
                                                     .ldsConfigSource(resourceName);
            node = new ListenerResourceNode(configSource, resourceName, context,
                                            this, ResourceNodeType.DYNAMIC);
            context.subscribe(node);
        }
    }

    @Override
    public void close() {
        if (!eventLoop().inEventLoop()) {
            eventLoop().execute(this::close);
            return;
        }
        node.close();
        super.close();
    }
}
