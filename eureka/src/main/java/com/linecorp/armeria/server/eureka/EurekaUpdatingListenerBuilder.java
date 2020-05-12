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

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.Map;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.eureka.DataCenterName;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.common.eureka.EurekaWebClient;
import com.linecorp.armeria.internal.common.eureka.InstanceInfoBuilder;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;

/**
 * Builds a {@link EurekaUpdatingListener}, which registers the server to Eureka.
 * <h2>Examples</h2>
 * <pre>{@code
 * EurekaUpdatingListener listener =
 *     EurekaUpdatingListener.builder("eureka.com:8001/eureka/v2", "i-00000000")
 *                           .setHostname("armeria.service.1")
 *                           .ipAddr("192.168.10.10")
 *                           .vipAddress("armeria.service.com:8080");
 *                           .build();
 * ServerBuilder sb = Server.builder();
 * sb.addListener(listener);
 * }</pre>
 */
public final class EurekaUpdatingListenerBuilder {

    public static final int DEFAULT_LEASE_RENEWAL_INTERVAL = 30;
    public static final int DEFAULT_LEASE_DURATION = 90;

    private final EurekaWebClient eurekaWebClient;
    private final InstanceInfoBuilder instanceInfoBuilder;

    /**
     * Creates a new instance.
     */
    EurekaUpdatingListenerBuilder(URI eurekaUri, String instanceId) {
        eurekaWebClient = new EurekaWebClient(WebClient.of(requireNonNull(eurekaUri, "eurekaUri")));
        instanceInfoBuilder = new InstanceInfoBuilder(instanceId);
    }

    /**
     * Sets the interval between renewal in seconds. {@value DEFAULT_LEASE_RENEWAL_INTERVAL} is used by default
     * and it's not recommended to modify this value. See
     * <a href="https://github.com/Netflix/eureka/wiki/Understanding-eureka-client-server-communication#renew">
     * renew</a>.
     */
    public EurekaUpdatingListenerBuilder renewalIntervalSeconds(int renewalIntervalSeconds) {
        instanceInfoBuilder.renewalIntervalSeconds(renewalIntervalSeconds);
        return this;
    }

    /**
     * Sets the lease duration in seconds. {@value DEFAULT_LEASE_DURATION} is used by default and it's
     * not recommended to modify this value. See
     * <a href="https://github.com/Netflix/eureka/wiki/Understanding-eureka-client-server-communication#renew">
     * renew</a>.
     */
    public EurekaUpdatingListenerBuilder leaseDurationSeconds(int leaseDurationSeconds) {
        instanceInfoBuilder.leaseDurationSeconds(leaseDurationSeconds);
        return this;
    }

    /**
     * Sets the hostname. {@link Server#defaultHostname()} is set if not specified.
     */
    public EurekaUpdatingListenerBuilder hostname(String hostname) {
        instanceInfoBuilder.hostname(hostname);
        return this;
    }

    /**
     * Sets the name of the application. {@link #hostname(String)} is set if not specified.
     */
    public EurekaUpdatingListenerBuilder appName(String appName) {
        instanceInfoBuilder.appName(appName);
        return this;
    }

    /**
     * Sets the group name of the application.
     */
    public EurekaUpdatingListenerBuilder appGroupName(String appGroupName) {
        instanceInfoBuilder.appGroupName(appGroupName);
        return this;
    }

    /**
     * Sets the IP address. {@link SystemInfo#defaultNonLoopbackIpV4Address()} is set if not specified.
     */
    public EurekaUpdatingListenerBuilder ipAddr(String ipAddr) {
        instanceInfoBuilder.ipAddr(ipAddr);
        return this;
    }

    /**
     * Sets the port used for {@link SessionProtocol#HTTP}.
     */
    public EurekaUpdatingListenerBuilder port(int port) {
        instanceInfoBuilder.port(port);
        return this;
    }

    /**
     * Sets the port used for {@link SessionProtocol#HTTPS}.
     */
    public EurekaUpdatingListenerBuilder securePort(int securePort) {
        instanceInfoBuilder.securePort(securePort);
        return this;
    }

    /**
     * Sets the VIP address. {@link #hostname(String)} is set if not specified.
     */
    public EurekaUpdatingListenerBuilder vipAddress(String vipAddress) {
        instanceInfoBuilder.vipAddress(vipAddress);
        return this;
    }

    /**
     * Sets the secure VIP address. {@link #hostname(String)} is set if not specified.
     */
    public EurekaUpdatingListenerBuilder secureVipAddress(String secureVipAddress) {
        instanceInfoBuilder.secureVipAddress(secureVipAddress);
        return this;
    }

    /**
     * Sets the home page URL.
     */
    public EurekaUpdatingListenerBuilder homePageUrl(String homePageUrl) {
        instanceInfoBuilder.homePageUrl(homePageUrl);
        return this;
    }

    /**
     * Sets the status page URL.
     */
    public EurekaUpdatingListenerBuilder statusPageUrl(String statusPageUrl) {
        instanceInfoBuilder.statusPageUrl(statusPageUrl);
        return this;
    }

    /**
     * Sets the health check URL. If {@link HealthCheckService} is added to {@link ServerBuilder} and
     * {@linkplain Server#activePort(SessionProtocol) Server.activePort(SessionProtocol.HTTP)} returns
     * an active port, then this URL will be automatically create using the information of the
     * {@link HealthCheckService}.
     */
    public EurekaUpdatingListenerBuilder healthCheckUrl(String healthCheckUrl) {
        instanceInfoBuilder.healthCheckUrl(healthCheckUrl);
        return this;
    }

    /**
     * Sets the secure health check URL. If {@link HealthCheckService} is added to {@link ServerBuilder} and
     * {@linkplain Server#activePort(SessionProtocol) Server.activePort(SessionProtocol.HTTPS)} returns
     * an active port, then this URL will be automatically create using the information of the
     * {@link HealthCheckService}.
     */
    public EurekaUpdatingListenerBuilder secureHealthCheckUrl(String secureHealthCheckUrl) {
        instanceInfoBuilder.secureHealthCheckUrl(secureHealthCheckUrl);
        return this;
    }

    /**
     * Sets the metadata.
     */
    public EurekaUpdatingListenerBuilder metadata(Map<String, String> metadata) {
        instanceInfoBuilder.metadata(metadata);
        return this;
    }

    /**
     * Sets the name of the data center.
     */
    public EurekaUpdatingListenerBuilder dataCenterName(DataCenterName dataCenterName) {
        instanceInfoBuilder.dataCenterName(dataCenterName);
        return this;
    }

    /**
     * Sets the metadata of the data center.
     */
    public EurekaUpdatingListenerBuilder dataCenterMetadata(Map<String, String> dataCenterMetadata) {
        instanceInfoBuilder.dataCenterMetadata(dataCenterMetadata);
        return this;
    }

    /**
     * Returns a newly-created {@link EurekaUpdatingListener} based on the properties of this builder.
     */
    public EurekaUpdatingListener build() {
        return new EurekaUpdatingListener(eurekaWebClient, instanceInfoBuilder.build());
    }
}
