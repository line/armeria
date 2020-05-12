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
package com.linecorp.armeria.server.eureka;

import static com.linecorp.armeria.internal.common.eureka.InstanceInfoBuilder.disabledPort;
import static java.util.Objects.requireNonNull;

import java.net.Inet4Address;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.common.eureka.EurekaWebClient;
import com.linecorp.armeria.internal.common.eureka.InstanceInfo;
import com.linecorp.armeria.internal.common.eureka.InstanceInfo.InstanceStatus;
import com.linecorp.armeria.internal.common.eureka.InstanceInfo.PortWrapper;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RoutePathType;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;

import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * A {@link ServerListener} which registers the current {@link Server} to Eureka. This
 * {@link EurekaUpdatingListener} sends renewal requests periodically so that the {@link Server} is not removed
 * from the registry. When the {@link Server} stops, This {@link EurekaUpdatingListener} deregister the
 * {@link Server} from Eureka by sending the cancel request.
 */
public final class EurekaUpdatingListener extends ServerListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(EurekaUpdatingListener.class);

    /**
     * Returns a new {@link EurekaUpdatingListenerBuilder} created with the specified {@code eurekaUri} and
     * {@code instanceId}.
     */
    public static EurekaUpdatingListenerBuilder builder(String eurekaUri, String instanceId) {
        return builder(URI.create(requireNonNull(eurekaUri, "eurekaUri")), instanceId);
    }

    /**
     * Returns a new {@link EurekaUpdatingListenerBuilder} created with the specified {@code eurekaUri} and
     * {@code instanceId}.
     */
    public static EurekaUpdatingListenerBuilder builder(URI eurekaUri, String instanceId) {
        return new EurekaUpdatingListenerBuilder(eurekaUri, instanceId);
    }

    private final EurekaWebClient client;
    private final InstanceInfo instanceInfo;
    @Nullable
    private volatile ScheduledFuture<?> heartBeatFuture;

    @Nullable
    private volatile String appName; // Set when serverStarted is called.
    private volatile boolean closed;

    /**
     * Creates a new instance.
     */
    EurekaUpdatingListener(EurekaWebClient client, InstanceInfo instanceInfo) {
        this.client = client;
        this.instanceInfo = instanceInfo;
    }

    @Override
    public void serverStarted(Server server) throws Exception {
        final InstanceInfo newInfo = fillAndCreateNewInfo(instanceInfo, server);

        try (ClientRequestContextCaptor contextCaptor = Clients.newContextCaptor()) {
            final HttpResponse response = client.register(newInfo);
            final ClientRequestContext ctx = contextCaptor.get();
            response.aggregateWithPooledObjects(ctx.eventLoop(), ctx.alloc()).handle((res, cause) -> {
                if (closed) {
                    return null;
                }
                try {
                    if (cause != null) {
                        logger.warn("Failed to register {} to Eureka: {}",
                                    newInfo.getHostName(), client.uri(), cause);
                        return null;
                    }
                    final ResponseHeaders headers = res.headers();
                    if (headers.status() != HttpStatus.NO_CONTENT) {
                        logger.warn("Failed to register {} to Eureka: {}. (status: {}, content: {})",
                                    newInfo.getHostName(), client.uri(), headers.status(), res.contentUtf8());
                    }
                    logger.info("Registered {} to Eureka: {}", newInfo.getHostName(), client.uri());
                    scheduleHeartBeat(ctx, newInfo);
                    return null;
                } finally {
                    ReferenceCountUtil.release(res.content());
                }
            });
        }
    }

    private void scheduleHeartBeat(ClientRequestContext ctx, InstanceInfo newInfo) {
        heartBeatFuture = ctx.eventLoop().schedule(new HeartBeatTask(ctx, newInfo),
                                                   newInfo.getLeaseInfo().getRenewalIntervalInSecs(),
                                                   TimeUnit.SECONDS);
    }

    private InstanceInfo fillAndCreateNewInfo(InstanceInfo oldInfo, Server server) {
        final String defaultHostname = server.defaultHostname();
        final String hostName = oldInfo.getHostName() != null ? oldInfo.getHostName() : defaultHostname;
        appName = oldInfo.getAppName() != null ? oldInfo.getAppName() : defaultHostname;

        final Inet4Address defaultInet4Address = SystemInfo.defaultNonLoopbackIpV4Address();
        final String defaultIpAddr = defaultInet4Address != null ? defaultInet4Address.getHostAddress()
                                                                 : null;
        final String ipAddr = oldInfo.getIpAddr() != null ? oldInfo.getIpAddr() : defaultIpAddr;
        final PortWrapper oldPortWrapper = oldInfo.getPort();
        final PortWrapper portWrapper = portWrapper(server, oldPortWrapper, SessionProtocol.HTTP);
        final PortWrapper oldSecurePortWrapper = oldInfo.getSecurePort();
        final PortWrapper securePortWrapper = portWrapper(server, oldSecurePortWrapper, SessionProtocol.HTTPS);

        final String vipAddress = vipAddress(oldInfo.getVipAddress(), hostName, portWrapper);
        final String secureVipAddress = vipAddress(oldInfo.getSecureVipAddress(), hostName, securePortWrapper);

        final Optional<ServiceConfig> healthCheckService =
                server.serviceConfigs()
                      .stream()
                      .filter(cfg -> cfg.service().as(HealthCheckService.class) != null)
                      .findFirst();

        final String hostnameOrIpAddr;
        if (oldInfo.getHostName() != null) {
            hostnameOrIpAddr = oldInfo.getHostName();
        } else if (ipAddr != null) {
            hostnameOrIpAddr = ipAddr;
        } else {
            hostnameOrIpAddr = hostName;
        }
        final String healthCheckUrl = healthCheckUrl(hostnameOrIpAddr, oldInfo.getHealthCheckUrl(), portWrapper,
                                                     healthCheckService, SessionProtocol.HTTP);
        final String secureHealthCheckUrl =
                healthCheckUrl(hostnameOrIpAddr, oldInfo.getSecureHealthCheckUrl(), securePortWrapper,
                               healthCheckService, SessionProtocol.HTTPS);

        return new InstanceInfo(oldInfo.getInstanceId(), appName, oldInfo.getAppGroupName(), hostName, ipAddr,
                                vipAddress, secureVipAddress, portWrapper, securePortWrapper, InstanceStatus.UP,
                                oldInfo.getHomePageUrl(), oldInfo.getStatusPageUrl(), healthCheckUrl,
                                secureHealthCheckUrl, oldInfo.getDataCenterInfo(),
                                oldInfo.getLeaseInfo(), oldInfo.getMetadata());
    }

    private static PortWrapper portWrapper(Server server, PortWrapper oldPortWrapper,
                                           SessionProtocol protocol) {
        if (oldPortWrapper.isEnabled()) {
            for (ServerPort serverPort : server.activePorts().values()) {
                if (serverPort.hasProtocol(protocol) &&
                    serverPort.localAddress().getPort() == oldPortWrapper.getPort()) {
                    return oldPortWrapper;
                }
            }
            logger.warn("The port number: {} (expected one of activePorts: {})",
                        oldPortWrapper.getPort(), server.activePorts());
        }

        final ServerPort serverPort = server.activePort(protocol);
        if (serverPort == null) {
            return disabledPort;
        }
        return new PortWrapper(true, serverPort.localAddress().getPort());
    }

    @Nullable
    private static String vipAddress(@Nullable String vipAddress, String hostName, PortWrapper portWrapper) {
        if (!portWrapper.isEnabled()) {
            return null;
        }
        return vipAddress != null ? vipAddress : hostName + ':' + portWrapper.getPort();
    }

    @Nullable
    private static String healthCheckUrl(String hostnameOrIpAddr, @Nullable String oldHealthCheckUrl,
                                         PortWrapper portWrapper,
                                         Optional<ServiceConfig> healthCheckService,
                                         SessionProtocol sessionProtocol) {
        if (oldHealthCheckUrl != null) {
            return oldHealthCheckUrl;
        }
        if (!portWrapper.isEnabled() || !healthCheckService.isPresent()) {
            return null;
        }
        final ServiceConfig healthCheckServiceConfig = healthCheckService.get();
        final Route route = healthCheckServiceConfig.route();
        if (route.pathType() != RoutePathType.EXACT && route.pathType() != RoutePathType.PREFIX) {
            return null;
        }

        return sessionProtocol.uriText() + "://" + hostnameOrIpAddr + ':' + portWrapper.getPort() +
               route.paths().get(0);
    }

    @Override
    public void serverStopping(Server server) throws Exception {
        closed = true;
        final ScheduledFuture<?> heartBeatFuture = this.heartBeatFuture;
        if (heartBeatFuture != null) {
            heartBeatFuture.cancel(false);
        }
        final String appName = this.appName;
        if (appName != null) {
            client.cancel(appName, instanceInfo.getInstanceId()).aggregate().handle((res, cause) -> {
                if (cause != null) {
                    logger.warn("Failed to deregister from Eureka: {}", client.uri(), cause);
                } else if (!res.status().isSuccess()) {
                    logger.warn("Failed to deregister from Eureka: {} (status: {}, content: {})",
                                client.uri(), res.status(), res.contentUtf8());
                }
                return null;
            });
        }
    }

    private class HeartBeatTask implements Runnable {

        private final ClientRequestContext ctx;
        private final InstanceInfo instanceInfo;

        HeartBeatTask(ClientRequestContext ctx, InstanceInfo instanceInfo) {
            this.ctx = ctx;
            this.instanceInfo = instanceInfo;
        }

        @Override
        public void run() {
            final String appName = instanceInfo.getAppName();
            assert appName != null;
            client.sendHeartBeat(appName, instanceInfo.getInstanceId(), instanceInfo, null)
                  .aggregateWithPooledObjects(ctx.eventLoop(), ctx.alloc())
                  .handle((res, cause) -> {
                      try {
                          if (closed) {
                              return null;
                          }

                          // The information of this instance is removed from the registry when the heart beats
                          // fail consecutive three times, so we don't retry.
                          // See https://github.com/Netflix/eureka/wiki/Understanding-eureka-client-server-communication#renew
                          if (cause != null) {
                              logger.warn("Failed to send a heart beat to Eureka: {}", client.uri(), cause);
                          } else if (res.headers().status() != HttpStatus.OK) {
                              logger.warn("Failed to send heart beat to Eureka: {}, (status: {}, content: {})",
                                          client.uri(), res.headers().status(), res.contentUtf8());
                          }
                          heartBeatFuture = ctx.eventLoop().schedule(
                                  this, instanceInfo.getLeaseInfo().getRenewalIntervalInSecs(),
                                  TimeUnit.SECONDS);
                          return null;
                      } finally {
                          ReferenceCountUtil.release(res.content());
                      }
                  });
        }
    }
}
