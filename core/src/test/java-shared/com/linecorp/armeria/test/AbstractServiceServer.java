/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.test;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;

import io.netty.util.internal.PlatformDependent;

public abstract class AbstractServiceServer {
    private Server server;

    protected abstract void configureServer(ServerBuilder sb) throws Exception;

    @SuppressWarnings("unchecked")
    public <T extends AbstractServiceServer> T start() throws Exception {
        ServerBuilder sb = new ServerBuilder().port(0, SessionProtocol.HTTP);
        configureServer(sb);
        server = sb.build();

        try {
            server.start().get();
        } catch (InterruptedException e) {
            PlatformDependent.throwException(e);
        }
        return (T) this;
    }

    public int port() {
        ServerPort port = server.activePort()
                                .orElseThrow(() -> new IllegalStateException("server not started yet"));
        return port.localAddress().getPort();
    }

    public void stop() {
        server.stop();
    }
}
