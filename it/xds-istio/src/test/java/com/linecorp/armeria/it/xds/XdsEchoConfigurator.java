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
package com.linecorp.armeria.it.xds;

import java.net.InetSocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerConfigurator;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.server.XdsServerPlugin;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

public final class XdsEchoConfigurator implements ServerConfigurator {

    private static final Logger logger = LoggerFactory.getLogger(XdsEchoConfigurator.class);

    private static final String LISTENER_NAME = "virtualInbound";
    private static final int SERVER_PORT = 8080;

    @Override
    public void reconfigure(ServerBuilder sb) {
        sb.service("/echo", (ctx, req) -> {
            final InetSocketAddress remoteAddr = ctx.remoteAddress();
            final InetSocketAddress localAddr = ctx.localAddress();
            final String body = "{\"remoteIp\":\"" + remoteAddr.getAddress().getHostAddress() + "\"," +
                                "\"remotePort\":" + remoteAddr.getPort() + ',' +
                                "\"localIp\":\"" + localAddr.getAddress().getHostAddress() + "\"," +
                                "\"localPort\":" + localAddr.getPort() + '}';
            return HttpResponse.of(MediaType.JSON, body);
        });
        sb.decorator(LoggingService.newDecorator());

        final String rewritten = XdsResourceReader.readBootstrap();
        final Bootstrap bootstrap = XdsResourceReader.fromJson(rewritten, Bootstrap.class);

        final XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
        // Use the same port as the server so the xDS plugin's connection acceptor
        // and TLS provider apply to the main server port. Armeria merges duplicate
        // port entries (the one from IstioPodEntryPoint and this one) automatically.
        final XdsServerPlugin plugin =
                XdsServerPlugin.of(xdsBootstrap, LISTENER_NAME, SERVER_PORT);
        sb.plugin(plugin);
    }
}
