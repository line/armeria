/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

/**
 * Builds a new {@link VirtualHost}.
 *
 * <p>This class can only be created through the {@link ServerBuilder#withDefaultVirtualHost()} or
 * {@link ServerBuilder#withVirtualHost(String)} method of the {@link ServerBuilder}.
 *
 * <p>Call {@link #and()} method can also return to {@link ServerBuilder}.
 *
 * @see ServerBuilder
 * @see PathMapping
 * @see VirtualHostBuilder
 */
public final class ChainedVirtualHostBuilder extends AbstractVirtualHostBuilder<ChainedVirtualHostBuilder> {

    private final ServerBuilder serverBuilder;

    /**
     * Creates a new {@link ChainedVirtualHostBuilder} whose hostname pattern is {@code "*"} (match-all).
     *
     * @param serverBuilder the parent {@link ServerBuilder} to be returned by {@link #and()}.
     */
    ChainedVirtualHostBuilder(ServerBuilder serverBuilder) {
        requireNonNull(serverBuilder, "serverBuilder");
        this.serverBuilder = serverBuilder;
    }

    /**
     * Creates a new {@link ChainedVirtualHostBuilder} with the specified hostname pattern.
     *
     * @param hostnamePattern the hostname pattern of this virtual host.
     * @param serverBuilder the parent {@link ServerBuilder} to be returned by {@link #and()}.
     */
    ChainedVirtualHostBuilder(String hostnamePattern, ServerBuilder serverBuilder) {
        super(hostnamePattern);

        requireNonNull(serverBuilder, "serverBuilder");
        this.serverBuilder = serverBuilder;
    }

    /**
     * Creates a new {@link ChainedVirtualHostBuilder} with
     * the default host name and the specified hostname pattern.
     *
     * @param defaultHostname the default hostname of this virtual host.
     * @param hostnamePattern the hostname pattern of this virtual host.
     * @param serverBuilder the parent {@link ServerBuilder} to be returned by {@link #and()}.
     */
    ChainedVirtualHostBuilder(String defaultHostname, String hostnamePattern, ServerBuilder serverBuilder) {
        super(defaultHostname, hostnamePattern);

        requireNonNull(serverBuilder, "serverBuilder");
        this.serverBuilder = serverBuilder;
    }

    /**
     * Returns the parent {@link ServerBuilder}.
     *
     * @return serverBuilder the parent {@link ServerBuilder}.
     */
    public ServerBuilder and() {
        return serverBuilder;
    }
}
