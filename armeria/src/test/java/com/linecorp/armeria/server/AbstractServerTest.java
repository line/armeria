/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import org.junit.AfterClass;
import org.junit.Before;

import com.linecorp.armeria.common.SessionProtocol;

import io.netty.util.concurrent.Future;
import io.netty.util.internal.PlatformDependent;

public abstract class AbstractServerTest {

    private static final Object lock = new Object();

    private static Server server;
    private static int httpPort;
    private static int httpsPort;

    @Before
    public void startServer() throws Exception {
        synchronized (lock) {
            if (server != null) {
                return;
            }

            final ServerBuilder sb = new ServerBuilder();
            configureServer(sb);
            server = sb.build();

            try {
                server.start().sync();
            } catch (InterruptedException e) {
                PlatformDependent.throwException(e);
            }

            httpPort = server.activePorts().values().stream()
                             .filter(p1 -> p1.protocol() == SessionProtocol.HTTP).findAny()
                             .flatMap(p -> Optional.of(p.localAddress().getPort())).orElse(-1);

            httpsPort = server.activePorts().values().stream()
                              .filter(p1 -> p1.protocol() == SessionProtocol.HTTPS).findAny()
                              .flatMap(p -> Optional.of(p.localAddress().getPort())).orElse(-1);
        }
    }

    protected abstract void configureServer(ServerBuilder sb) throws Exception;

    @AfterClass
    public static void stopServer() throws Exception {
        stopServer(false);
    }

    protected static void stopServer(boolean await) throws Exception {
        synchronized (lock) {
            if (server == null) {
                return;
            }

            Future<Void> future = server.stop();
            server = null;
            if (await) {
                future.sync();
            }
        }
    }

    protected static Server server() {
        if (server == null) {
            throw new IllegalStateException("server did not start.");
        }
        return server;
    }

    protected static int httpPort() {
        return httpPort;
    }

    protected static int httpsPort() {
        return httpsPort;
    }

    protected static String uri(String path) {
        return httpPort > 0 ? httpUri(path) : httpsUri(path);
    }

    protected static String httpUri(String path) {
        validatePath(path);

        if (httpPort <= 0) {
            throw new IllegalStateException("HTTP port not open");
        }

        return "http://127.0.0.1:" + httpPort + path;
    }

    protected static String httpsUri(String path) {
        validatePath(path);

        if (httpsPort <= 0) {
            throw new IllegalStateException("HTTPS port not open");
        }

        return "https://127.0.0.1:" + httpsPort + path;
    }

    private static void validatePath(String path) {
        if (!requireNonNull(path, "path").startsWith("/")) {
            throw new IllegalArgumentException("path: " + path + " (expected: an absolute path)");
        }
    }
}
