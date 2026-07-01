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
package com.linecorp.armeria.xds.server;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.xds.XdsBootstrap;

/**
 * Builds a new {@link XdsServerPlugin}.
 */
@UnstableApi
public final class XdsServerPluginBuilder {

    private static final Duration DEFAULT_READY_TIMEOUT = Duration.ofSeconds(30);

    private final XdsBootstrap bootstrap;
    private final String listenerName;
    private final List<ServerPort> serverPorts = new ArrayList<>();
    private Duration readyTimeout = DEFAULT_READY_TIMEOUT;

    XdsServerPluginBuilder(XdsBootstrap bootstrap, String listenerName, int... ports) {
        this.bootstrap = requireNonNull(bootstrap, "bootstrap");
        this.listenerName = requireNonNull(listenerName, "listenerName");
        for (int port : ports) {
            serverPorts.add(new ServerPort(port, SessionProtocol.HTTP, SessionProtocol.HTTPS));
        }
    }

    /**
     * Adds a {@link ServerPort} to listen on.
     */
    public XdsServerPluginBuilder port(ServerPort serverPort) {
        requireNonNull(serverPort, "serverPort");
        checkArgument(serverPort.hasProtocol(SessionProtocol.HTTP) &&
                      serverPort.hasProtocol(SessionProtocol.HTTPS),
                      "serverPort must support both HTTP and HTTPS: %s", serverPort);
        serverPorts.add(serverPort);
        return this;
    }

    /**
     * Sets the maximum time to wait for the first xDS snapshot to be resolved before
     * the server starts. Defaults to 30 seconds.
     */
    public XdsServerPluginBuilder readyTimeout(Duration readyTimeout) {
        requireNonNull(readyTimeout, "readyTimeout");
        checkArgument(!readyTimeout.isNegative(), "readyTimeout: %s (expected: >= 0)", readyTimeout);
        this.readyTimeout = readyTimeout;
        return this;
    }

    /**
     * Builds a new {@link XdsServerPlugin}.
     */
    public XdsServerPlugin build() {
        final List<ServerPort> ports;
        if (serverPorts.isEmpty()) {
            ports = ImmutableList.of(new ServerPort(0, SessionProtocol.HTTP, SessionProtocol.HTTPS));
        } else {
            ports = ImmutableList.copyOf(serverPorts);
        }
        return new XdsServerPlugin(bootstrap, listenerName, ports, readyTimeout);
    }
}
