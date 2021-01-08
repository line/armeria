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

import static com.linecorp.armeria.server.eureka.InstanceInfoBuilder.disabledPort;
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
import com.linecorp.armeria.client.endpoint.EndpointGroup;
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

import io.netty.channel.EventLoop;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * A {@link ServerListener} which registers the current {@link Server} to Eureka.
 * {@link EurekaUpdatingListener} sends renewal requests periodically so that the {@link Server} is not removed
 * from the registry. When the {@link Server} stops, {@link EurekaUpdatingListener} deregisters the
 * {@link Server} from Eureka by sending a cancellation request.
 */
public final class EurekaUpdatingListener extends ServerListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(EurekaUpdatingListener.class);

    /**
     * Returns a new {@link EurekaUpdatingListener} which registers the current {@link Server} to
     * the specified {@code eurekaUri}.
     */
    public static EurekaUpdatingListener of(String eurekaUri) {
        return of(URI.create(requireNonNull(eurekaUri, "eurekaUri")));
    }

    /**
     * Returns a new {@link EurekaUpdatingListener} which registers the current {@link Server} to
     * the specified {@code eurekaUri}.
     */
    public static EurekaUpdatingListener of(URI eurekaUri) {
        return new EurekaUpdatingListenerBuilder(eurekaUri).build();
    }

    /**
     * Returns a new {@link EurekaUpdatingListener} which registers the current {@link Server} to
     * the specified {@link EndpointGroup}.
     */
    public static EurekaUpdatingListener of(
            SessionProtocol sessionProtocol, EndpointGroup endpointGroup) {
        return new EurekaUpdatingListenerBuilder(sessionProtocol, endpointGroup, null).build();
    }

    /**
     * Returns a new {@link EurekaUpdatingListener} which registers the current {@link Server} to
     * the specified {@link EndpointGroup} under the specified {@code path}.
     */
    public static EurekaUpdatingListener of(
            SessionProtocol sessionProtocol, EndpointGroup endpointGroup, String path) {
        return new EurekaUpdatingListenerBuilder(
                sessionProtocol, endpointGroup, requireNonNull(path, "path")).build();
    }

    /**
     * Returns a new {@link EurekaUpdatingListenerBuilder} created with the specified {@code eurekaUri}.
     */
    public static EurekaUpdatingListenerBuilder builder(String eurekaUri) {
        return builder(URI.create(requireNonNull(eurekaUri, "eurekaUri")));
    }

    /**
     * Returns a new {@link EurekaUpdatingListenerBuilder} created with the specified {@code eurekaUri}.
     */
    public static EurekaUpdatingListenerBuilder builder(URI eurekaUri) {
        return new EurekaUpdatingListenerBuilder(eurekaUri);
    }

    /**
     * Returns a new {@link EurekaUpdatingListenerBuilder} created with the specified {@link SessionProtocol}
     * and {@link EndpointGroup}.
     */
    public static EurekaUpdatingListenerBuilder builder(
            SessionProtocol sessionProtocol, EndpointGroup endpointGroup) {
        return new EurekaUpdatingListenerBuilder(sessionProtocol, endpointGroup, null);
    }

    /**
     * Returns a new {@link EurekaUpdatingListenerBuilder} created with the specified {@link SessionProtocol},
     * {@link EndpointGroup} and path.
     */
    public static EurekaUpdatingListenerBuilder builder(
            SessionProtocol sessionProtocol, EndpointGroup endpointGroup, String path) {
        return new EurekaUpdatingListenerBuilder(sessionProtocol, endpointGroup, requireNonNull(path, "path"));
    }

    private final EurekaWebClient client;
    private InstanceInfo instanceInfo;
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
        this.instanceInfo = fillAndCreateNewInfo(instanceInfo, server);
        register(instanceInfo);
    }

    private void register(InstanceInfo instanceInfo) {
        try (ClientRequestContextCaptor contextCaptor = Clients.newContextCaptor()) {
            final HttpResponse response = client.register(instanceInfo);
            final ClientRequestContext ctx = contextCaptor.getOrNull();
            response.aggregate().handle((res, cause) -> {
                if (closed) {
                    return null;
                }
                if (cause != null) {
                    logger.warn("Failed to register {} to Eureka: {}",
                                instanceInfo.getHostName(), client.uri(), cause);
                    return null;
                }

                final ResponseHeaders headers = res.headers();
                if (headers.status() != HttpStatus.NO_CONTENT) {
                    logger.warn("Failed to register {} to Eureka: {}. (status: {}, content: {})",
                                instanceInfo.getHostName(), client.uri(), headers.status(), res.contentUtf8());
                } else {
                    logger.info("Registered {} to Eureka: {}", instanceInfo.getHostName(), client.uri());
                    assert ctx != null;
                    scheduleHeartBeat(ctx.eventLoop().withoutContext(), instanceInfo);
                }
                return null;
            });
        }
    }

    private void scheduleHeartBeat(EventLoop eventLoop, InstanceInfo newInfo) {
        heartBeatFuture = eventLoop.schedule(new HeartBeatTask(eventLoop, newInfo),
                                             newInfo.getLeaseInfo().getRenewalIntervalInSecs(),
                                             TimeUnit.SECONDS);
    }

    private InstanceInfo fillAndCreateNewInfo(InstanceInfo oldInfo, Server server) {
        final String defaultHostname = server.defaultHostname();
        final String hostName = oldInfo.getHostName() != null ? oldInfo.getHostName() : defaultHostname;
        appName = oldInfo.getAppName() != null ? oldInfo.getAppName() : hostName;
        final String instanceId = oldInfo.getInstanceId() != null ? oldInfo.getInstanceId() : hostName;

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

        return new InstanceInfo(instanceId, appName, oldInfo.getAppGroupName(), hostName, ipAddr,
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
            logger.warn("The specified port number {} does not exist. (expected one of activePorts: {})",
                        oldPortWrapper.getPort(), server.activePorts());
            return oldPortWrapper;
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

        return sessionProtocol.uriText() + "://" + hostnameOrIpAddr(hostnameOrIpAddr) +
               ':' + portWrapper.getPort() + route.paths().get(0);
    }

    private static String hostnameOrIpAddr(String hostnameOrIpAddr) {
        if (NetUtil.isValidIpV6Address(hostnameOrIpAddr) && hostnameOrIpAddr.charAt(0) != '[') {
            return '[' + hostnameOrIpAddr + ']';
        }
        return hostnameOrIpAddr;
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
            final String instanceId = instanceInfo.getInstanceId();
            assert instanceId != null;
            client.cancel(appName, instanceId).aggregate().handle((res, cause) -> {
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

        private final EventLoop eventLoop;
        private final InstanceInfo instanceInfo;

        HeartBeatTask(EventLoop eventLoop, InstanceInfo instanceInfo) {
            this.eventLoop = eventLoop;
            this.instanceInfo = instanceInfo;
        }

        @Override
        public void run() {
            final String appName = instanceInfo.getAppName();
            final String instanceId = instanceInfo.getInstanceId();
            assert appName != null;
            assert instanceId != null;
            client.sendHeartBeat(appName, instanceId, instanceInfo, null)
                  .aggregate()
                  .handle((res, cause) -> {
                      if (closed) {
                          return null;
                      }

                      if (cause != null) {
                          logger.warn("Failed to send a heart beat to Eureka: {}", client.uri(), cause);
                      } else {
                          final HttpStatus status = res.status();

                          if (status == HttpStatus.OK) {
                              logger.debug("Sent a heart beat to Eureka: {}", client.uri());
                          } else if (status == HttpStatus.NOT_FOUND) {
                              // The information of this instance is removed from the registry when
                              // the heart beats fail consecutive three times, so we try to re-registration.
                              // See https://github.com/Netflix/eureka/wiki/Understanding-eureka-client-server-communication#renew
                              logger.warn("Instance {}/{} no longer registered with Eureka." +
                                          " Attempting re-registration.",
                                          appName, instanceId);
                              register(instanceInfo);
                              return null;
                          } else {
                              logger.warn("Failed to send a heart beat to Eureka: {}, " +
                                          "(status: {}, content: {})",
                                          client.uri(), res.status(), res.contentUtf8());
                          }
                      }

                      heartBeatFuture = eventLoop.schedule(
                              this, instanceInfo.getLeaseInfo().getRenewalIntervalInSecs(),
                              TimeUnit.SECONDS);
                      return null;
                  });
        }
    }
}
