/*
 * Copyright 2026 LY Corporation
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

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.netty.util.concurrent.EventExecutor;

final class UncloseableXdsBootstrap implements XdsBootstrap {

    private final XdsBootstrap delegate;

    UncloseableXdsBootstrap(XdsBootstrap delegate) {
        this.delegate = delegate;
    }

    @Override
    public ListenerRoot listenerRoot(String resourceName) {
        return delegate.listenerRoot(resourceName);
    }

    @Override
    public ClusterRoot clusterRoot(String resourceName) {
        return delegate.clusterRoot(resourceName);
    }

    @Override
    public EventExecutor eventLoop() {
        return delegate.eventLoop();
    }

    @Override
    public Bootstrap bootstrap() {
        return delegate.bootstrap();
    }

    @Override
    public void close() {
    }
}
