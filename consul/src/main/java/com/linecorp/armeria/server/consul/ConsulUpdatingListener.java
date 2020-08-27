/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.server.consul;

import static java.util.Objects.requireNonNull;

import java.net.Inet4Address;
import java.net.URI;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.consul.Check;
import com.linecorp.armeria.internal.consul.ConsulClient;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServerPort;

/**
 * A {@link ServerListener} which registers the current {@link Server} to
 * <a href="https://www.consul.io">Consul</a>.
 */
public class ConsulUpdatingListener extends ServerListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ConsulUpdatingListener.class);

    /**
     * Returns a {@link ConsulUpdatingListenerBuilder} that builds {@link ConsulUpdatingListener}.
     * @param serviceName the service name which is registered into Consul.
     */
    public static ConsulUpdatingListenerBuilder builder(String serviceName) {
        return new ConsulUpdatingListenerBuilder(serviceName);
    }

    private final ConsulClient consulClient;
    private final String serviceName;

    @Nullable
    private final Endpoint endpoint;
    @Nullable
    private final Check check;
    @Nullable
    private String serviceId;

    ConsulUpdatingListener(ConsulClient consulClient, String serviceName, @Nullable Endpoint endpoint,
                           @Nullable URI checkUrl, @Nullable HttpMethod checkMethod, String checkInterval) {
        this.consulClient = requireNonNull(consulClient, "consulClient");
        this.serviceName = requireNonNull(serviceName, "serviceName");
        this.endpoint = endpoint;

        if (checkUrl != null) {
            final Check check = new Check();
            check.setHttp(checkUrl.toString());
            if (checkMethod != null) {
                check.setMethod(checkMethod.toString());
            }
            check.setInterval(checkInterval);
            this.check = check;
        } else {
            check = null;
        }
    }

    @Override
    public void serverStarted(Server server) throws Exception {
        final Endpoint endpoint = getEndpoint(server);
        final String serviceId = serviceName + '.' + Long.toHexString(ThreadLocalRandom.current().nextLong());
        consulClient.register(serviceId, serviceName, endpoint, check)
                    .aggregate()
                    .handle((res, cause) -> {
                  if (cause != null) {
                      logger.warn("Failed to register {}:{} to Consul: {}",
                                  endpoint.host(), endpoint.port(), consulClient.uri(), cause);
                      return null;
                  }

                  if (res.status() != HttpStatus.OK) {
                      logger.warn("Failed to register {}:{} to Consul: {}. (status: {}, content: {})",
                                  endpoint.host(), endpoint.port(), consulClient.uri(), res.status(),
                                  res.contentUtf8());
                      return null;
                  }

                  logger.info("Registered {}:{} to Consul: {}",
                              endpoint.host(), endpoint.port(), consulClient.uri());
                  this.serviceId = serviceId;
                  return null;
              });
    }

    private Endpoint getEndpoint(Server server) {
        if (endpoint != null) {
            if (endpoint.hasPort()) {
                warnIfInactivePort(server, endpoint.port());
            }
            return endpoint;
        }
        return defaultEndpoint(server);
    }

    private static Endpoint defaultEndpoint(Server server) {
        final ServerPort serverPort = server.activePort();
        assert serverPort != null;

        final Inet4Address inet4Address = SystemInfo.defaultNonLoopbackIpV4Address();
        final String host = inet4Address != null ? inet4Address.getHostAddress() : server.defaultHostname();
        return Endpoint.of(host, serverPort.localAddress().getPort());
    }

    private static void warnIfInactivePort(Server server, int port) {
        for (ServerPort serverPort : server.activePorts().values()) {
            if (serverPort.localAddress().getPort() == port) {
                return;
            }
        }
        logger.warn("The specified port number {} does not exist. (expected one of activePorts: {})",
                    port, server.activePorts());
    }

    @Override
    public void serverStopping(Server server) {
        if (serviceId != null) {
            consulClient.deregister(serviceId)
                        .aggregate()
                        .handle((res, cause) -> {
                      if (cause != null) {
                          logger.warn("Failed to deregister {} from Consul: {}",
                                      serviceId, consulClient.uri(), cause);
                      }
                      if (res.status() != HttpStatus.OK) {
                          logger.warn("Failed to deregister {} from Consul: {}. (status: {}, content: {})",
                                      serviceId, consulClient.uri(), res.status(),
                                      res.contentUtf8());
                      }
                      return null;
                  });
        }
    }
}
