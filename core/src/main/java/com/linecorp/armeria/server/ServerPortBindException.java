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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Joiner;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A {@link RuntimeException} raised when a {@link Server} fails to bind a {@link ServerPort}
 * while starting up, e.g. because the address is already in use.
 *
 * <p>Unlike the bare exception thrown by the underlying transport, this exception carries the
 * {@link ServerPort} that failed to bind, which can be retrieved via {@link #serverPort()} to
 * produce a port- and protocol-aware error message. The original failure is always preserved as
 * the {@linkplain #getCause() cause}.</p>
 */
@UnstableApi
public final class ServerPortBindException extends RuntimeException {

    private static final long serialVersionUID = -8252837343134789905L;

    private final ServerPort serverPort;

    /**
     * Creates a new instance.
     *
     * @param serverPort the {@link ServerPort} that failed to bind
     * @param cause the original failure, e.g. a {@link java.net.BindException}
     */
    public ServerPortBindException(ServerPort serverPort, Throwable cause) {
        super(toMessage(requireNonNull(serverPort, "serverPort")), requireNonNull(cause, "cause"));
        this.serverPort = serverPort;
    }

    private static String toMessage(ServerPort serverPort) {
        return "Failed to bind to " + Joiner.on('+').join(serverPort.protocols()) +
               " at " + serverPort.localAddress();
    }

    /**
     * Returns the {@link ServerPort} that failed to bind.
     */
    public ServerPort serverPort() {
        return serverPort;
    }
}
