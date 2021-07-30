/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.server.tomcat;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.SocketProcessorBase;
import org.apache.tomcat.util.net.SocketWrapperBase;

/**
 * A fake {@link AbstractEndpoint}.
 */
public final class ArmeriaEndpoint extends AbstractEndpoint {

    private static final Log log = LogFactory.getLog(ArmeriaEndpoint.class);

    @Override
    protected void createSSLContext(SSLHostConfig sslHostConfig) throws Exception {}

    @Override
    protected InetSocketAddress getLocalAddress() throws IOException {
        return null;
    }

    @Override
    public boolean isAlpnSupported() {
        return false;
    }

    @Override
    protected boolean getDeferAccept() {
        return false;
    }

    @Override
    protected SocketProcessorBase createSocketProcessor(SocketWrapperBase socketWrapper, SocketEvent event) {
        return null;
    }

    @Override
    public void bind() throws Exception {}

    @Override
    public void unbind() throws Exception {}

    @Override
    public void startInternal() throws Exception {}

    @Override
    public void stopInternal() throws Exception {}

    @Override
    protected Acceptor createAcceptor() {
        return null;
    }

    @Override
    protected Log getLog() {
        return log;
    }

    @Override
    protected void doCloseServerSocket() throws IOException {}
}
