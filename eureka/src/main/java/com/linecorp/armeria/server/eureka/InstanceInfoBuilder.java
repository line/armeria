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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.server.eureka.EurekaUpdatingListenerBuilder.DEFAULT_DATA_CENTER_NAME;
import static com.linecorp.armeria.server.eureka.EurekaUpdatingListenerBuilder.DEFAULT_LEASE_DURATION_SECONDS;
import static com.linecorp.armeria.server.eureka.EurekaUpdatingListenerBuilder.DEFAULT_LEASE_RENEWAL_INTERVAL_SECONDS;
import static java.util.Objects.requireNonNull;

import java.util.Map;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.eureka.DataCenterInfo;
import com.linecorp.armeria.internal.common.eureka.InstanceInfo;
import com.linecorp.armeria.internal.common.eureka.InstanceInfo.InstanceStatus;
import com.linecorp.armeria.internal.common.eureka.InstanceInfo.PortWrapper;
import com.linecorp.armeria.internal.common.eureka.LeaseInfo;

import io.netty.util.NetUtil;

/**
 * Builds an {@link InstanceInfo}.
 */
final class InstanceInfoBuilder {

    /**
     * The {@link PortWrapper} which represents the port which is disabled.
     */
    static final PortWrapper disabledPort = new PortWrapper(false, 0);

    private int renewalIntervalSeconds = DEFAULT_LEASE_RENEWAL_INTERVAL_SECONDS;
    private int leaseDurationSeconds = DEFAULT_LEASE_DURATION_SECONDS;

    @Nullable
    private String hostname;

    @Nullable
    private String instanceId;

    @Nullable
    private String appName;

    @Nullable
    private String appGroupName;

    @Nullable
    private String ipAddr;
    private PortWrapper port = disabledPort;
    private PortWrapper securePort = disabledPort;

    @Nullable
    private String vipAddress;
    @Nullable
    private String secureVipAddress;
    @Nullable
    private String homePageUrl;
    @Nullable
    private String statusPageUrl;
    @Nullable
    private String healthCheckUrl;
    @Nullable
    private String secureHealthCheckUrl;
    private Map<String, String> metadata = ImmutableMap.of();

    private String dataCenterName = DEFAULT_DATA_CENTER_NAME;
    private Map<String, String> dataCenterMetadata = ImmutableMap.of();

    /**
     * Sets the interval between renewal in seconds.
     */
    InstanceInfoBuilder renewalIntervalSeconds(int renewalIntervalSeconds) {
        checkArgument(renewalIntervalSeconds > 0,
                      "renewalIntervalInSecs: %s (expected: > 0)", renewalIntervalSeconds);
        this.renewalIntervalSeconds = renewalIntervalSeconds;
        return this;
    }

    /**
     * Sets the lease duration in seconds.
     */
    InstanceInfoBuilder leaseDurationSeconds(int leaseDurationSeconds) {
        checkArgument(leaseDurationSeconds > 0,
                      "durationInSecs: %s (expected: > 0)", leaseDurationSeconds);
        this.leaseDurationSeconds = leaseDurationSeconds;
        return this;
    }

    /**
     * Sets the hostname.
     */
    InstanceInfoBuilder hostname(String hostname) {
        this.hostname = requireNonNull(hostname, "hostname");
        return this;
    }

    /**
     * Sets the name of the application.
     */
    InstanceInfoBuilder instanceId(String instanceId) {
        this.instanceId = requireNonNull(instanceId, "instanceId");
        return this;
    }

    /**
     * Sets the name of the application.
     */
    InstanceInfoBuilder appName(String appName) {
        this.appName = requireNonNull(appName, "appName");
        return this;
    }

    /**
     * Sets the group name of the application.
     */
    InstanceInfoBuilder appGroupName(String appGroupName) {
        this.appGroupName = requireNonNull(appGroupName, "appGroupName");
        return this;
    }

    /**
     * Sets the IP address.
     */
    InstanceInfoBuilder ipAddr(String ipAddr) {
        requireNonNull(ipAddr, "ipAddr");
        validateIpAddr(ipAddr, "ipAddr");
        this.ipAddr = ipAddr;
        return this;
    }

    /**
     * Sets the port used for {@link SessionProtocol#HTTP}.
     */
    InstanceInfoBuilder port(int port) {
        checkArgument(port > 0, "port: %s (expected: > 0)", port);
        this.port = new PortWrapper(true, port);
        return this;
    }

    /**
     * Sets the port used for {@link SessionProtocol#HTTPS}.
     */
    InstanceInfoBuilder securePort(int securePort) {
        checkArgument(securePort > 0, "securePort: %s (expected: > 0)", securePort);
        this.securePort = new PortWrapper(true, securePort);
        return this;
    }

    /**
     * Sets the VIP address.
     */
    InstanceInfoBuilder vipAddress(String vipAddress) {
        this.vipAddress = requireNonNull(vipAddress, "vipAddress");
        return this;
    }

    /**
     * Sets the secure VIP address.
     */
    InstanceInfoBuilder secureVipAddress(String secureVipAddress) {
        this.secureVipAddress = requireNonNull(secureVipAddress, "secureVipAddress");
        return this;
    }

    /**
     * Sets the home page URL.
     */
    InstanceInfoBuilder homePageUrl(String homePageUrl) {
        this.homePageUrl = requireNonNull(homePageUrl, "homePageUrl");
        return this;
    }

    /**
     * Sets the status page URL.
     */
    InstanceInfoBuilder statusPageUrl(String statusPageUrl) {
        this.statusPageUrl = requireNonNull(statusPageUrl, "statusPageUrl");
        return this;
    }

    /**
     * Sets the health check URL.
     */
    InstanceInfoBuilder healthCheckUrl(String healthCheckUrl) {
        this.healthCheckUrl = requireNonNull(healthCheckUrl, "healthCheckUrl");
        return this;
    }

    /**
     * Sets the secure health check URL.
     */
    InstanceInfoBuilder secureHealthCheckUrl(String secureHealthCheckUrl) {
        this.secureHealthCheckUrl = requireNonNull(secureHealthCheckUrl, "secureHealthCheckUrl");
        return this;
    }

    /**
     * Sets the metadata.
     */
    InstanceInfoBuilder metadata(Map<String, String> metadata) {
        this.metadata = requireNonNull(metadata, "metadata");
        return this;
    }

    /**
     * Sets the name of the data center.
     */
    InstanceInfoBuilder dataCenterName(String dataCenterName) {
        this.dataCenterName = requireNonNull(dataCenterName, "dataCenterName");
        return this;
    }

    /**
     * Sets the metadata of the data center.
     */
    InstanceInfoBuilder dataCenterMetadata(Map<String, String> dataCenterMetadata) {
        this.dataCenterMetadata = requireNonNull(dataCenterMetadata, "dataCenterMetadata");
        return this;
    }

    /**
     * Returns a newly-created {@link InstanceInfo} based on the properties of this builder.
     */
    InstanceInfo build() {
        final LeaseInfo leaseInfo = new LeaseInfo(renewalIntervalSeconds, leaseDurationSeconds);
        return new InstanceInfo(instanceId, appName, appGroupName, hostname, ipAddr, vipAddress,
                                secureVipAddress, port, securePort, InstanceStatus.UP,
                                homePageUrl, statusPageUrl, healthCheckUrl, secureHealthCheckUrl,
                                new DataCenterInfo(dataCenterName, dataCenterMetadata),
                                leaseInfo, metadata);
    }

    private static void validateIpAddr(String ipAddr, String name) {
        if (!NetUtil.isValidIpV4Address(ipAddr) && !NetUtil.isValidIpV6Address(ipAddr)) {
            throw new IllegalArgumentException("Invalid " + name + ": " + ipAddr);
        }
    }
}
