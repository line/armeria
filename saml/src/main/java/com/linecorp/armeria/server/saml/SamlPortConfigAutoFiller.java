/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server.saml;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServerPort;

/**
 * Fill unspecified scheme and port in the {@link SamlPortConfigBuilder}. They will be resolved by
 * the primary server port after the server started.
 */
final class SamlPortConfigAutoFiller extends ServerListenerAdapter {

    private final SamlPortConfigBuilder builder;
    private final CompletableFuture<SamlPortConfig> future = new CompletableFuture<>();
    private final AtomicBoolean completed = new AtomicBoolean();

    @Nullable
    private SamlPortConfig config;

    SamlPortConfigAutoFiller(SamlPortConfigBuilder builder) {
        this.builder = builder;
    }

    /**
     * Returns a future of a {@link SamlPortConfig}.
     */
    CompletableFuture<SamlPortConfig> future() {
        return future;
    }

    /**
     * Returns a {@link SamlPortConfig}.
     */
    @Nullable
    SamlPortConfig config() {
        return config;
    }

    /**
     * Returns whether a {@link SamlPortConfig} has been configured.
     */
    boolean isDone() {
        return config != null;
    }

    @Override
    public void serverStarted(Server server) throws Exception {
        // Ensure that the following work will be done once.
        if (completed.compareAndSet(false, true)) {
            final ServerPort activePort = server.activePort();
            assert activePort != null;
            builder.setSchemeAndPortIfAbsent(activePort);
            assert builder.scheme() != null;
            config = new SamlPortConfig(builder.scheme(), builder.port());
            future.complete(config);
        }
    }
}
