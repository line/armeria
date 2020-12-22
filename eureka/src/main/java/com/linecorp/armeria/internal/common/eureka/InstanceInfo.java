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
package com.linecorp.armeria.internal.common.eureka;

import static com.google.common.base.MoreObjects.toStringHelper;
import static java.util.Objects.requireNonNull;

import java.util.Map;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;

/**
 * An instance information.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonRootName("instance")
public final class InstanceInfo {

    private static final Logger logger = LoggerFactory.getLogger(InstanceInfo.class);

    private final String instanceId;

    @Nullable
    private final String hostName;
    @Nullable
    private final String appName;
    @Nullable
    private final String appGroupName;
    @Nullable
    private final String ipAddr;
    @Nullable
    private final String vipAddress;
    @Nullable
    private final String secureVipAddress;

    private final PortWrapper port;
    private final PortWrapper securePort;
    private final InstanceStatus status;

    @Nullable
    private final String homePageUrl;
    @Nullable
    private final String statusPageUrl;
    @Nullable
    private final String healthCheckUrl;
    @Nullable
    private final String secureHealthCheckUrl;
    private final DataCenterInfo dataCenterInfo;
    private final LeaseInfo leaseInfo;
    private final Map<String, String> metadata;

    private final long lastUpdatedTimestamp;
    private final long lastDirtyTimestamp;

    /**
     * Creates a new instance.
     */
    public InstanceInfo(@Nullable @JsonProperty("instanceId") String instanceId,
                        @Nullable @JsonProperty("app") String appName,
                        @Nullable @JsonProperty("appGroupName") String appGroupName,
                        @Nullable @JsonProperty("hostName") String hostName,
                        @Nullable @JsonProperty("ipAddr") String ipAddr,
                        @Nullable @JsonProperty("vipAddress") String vipAddress,
                        @Nullable @JsonProperty("secureVipAddress") String secureVipAddress,
                        @JsonProperty("port") PortWrapper port,
                        @JsonProperty("securePort") PortWrapper securePort,
                        @JsonProperty("status") InstanceStatus status,
                        @Nullable @JsonProperty("homePageUrl") String homePageUrl,
                        @Nullable @JsonProperty("statusPageUrl") String statusPageUrl,
                        @Nullable @JsonProperty("healthCheckUrl") String healthCheckUrl,
                        @Nullable @JsonProperty("secureHealthCheckUrl") String secureHealthCheckUrl,
                        @JsonProperty("dataCenterInfo") DataCenterInfo dataCenterInfo,
                        @JsonProperty("leaseInfo") LeaseInfo leaseInfo,
                        @Nullable @JsonProperty("metadata") Map<String, String> metadata) {
        this.instanceId = instanceId;
        this.hostName = hostName;
        this.appName = appName;
        this.appGroupName = appGroupName;
        this.ipAddr = ipAddr;
        this.vipAddress = vipAddress;
        this.secureVipAddress = secureVipAddress;
        this.port = requireNonNull(port, "port");
        this.securePort = requireNonNull(securePort, "securePort");
        this.status = requireNonNull(status, "status");
        this.homePageUrl = homePageUrl;
        this.statusPageUrl = statusPageUrl;
        this.healthCheckUrl = healthCheckUrl;
        this.secureHealthCheckUrl = secureHealthCheckUrl;
        this.dataCenterInfo = dataCenterInfo;
        this.leaseInfo = requireNonNull(leaseInfo, "leaseInfo");
        if (metadata != null) {
            this.metadata = metadata;
        } else {
            this.metadata = ImmutableMap.of();
        }

        lastUpdatedTimestamp = System.currentTimeMillis();
        lastDirtyTimestamp = lastUpdatedTimestamp;
    }

    /**
     * Returns the ID of this instance.
     */
    @Nullable
    public String getInstanceId() {
        return instanceId;
    }

    /**
     * Return the name of the application which this instance belongs to.
     */
    @Nullable
    @JsonProperty("app")
    public String getAppName() {
        return appName;
    }

    /**
     * Return the group name of the application which this instance belongs to.
     */
    @Nullable
    public String getAppGroupName() {
        return appGroupName;
    }

    /**
     * Return the hostname of this instance.
     */
    @Nullable
    public String getHostName() {
        return hostName;
    }

    /**
     * Returns the IP address of this instance.
     */
    @Nullable
    public String getIpAddr() {
        return ipAddr;
    }

    /**
     * Returns the VIP address of this instance.
     */
    @Nullable
    public String getVipAddress() {
        return vipAddress;
    }

    /**
     * Returns the secure VIP address of this instance.
     */
    @Nullable
    public String getSecureVipAddress() {
        return secureVipAddress;
    }

    /**
     * Returns the {@link PortWrapper} of this instance.
     */
    public PortWrapper getPort() {
        return port;
    }

    /**
     * Returns the secure {@link PortWrapper} of this instance.
     */
    public PortWrapper getSecurePort() {
        return securePort;
    }

    /**
     * Returns the {@link InstanceStatus} of this instance.
     */
    public InstanceStatus getStatus() {
        return status;
    }

    /**
     * Returns the home page URL of this instance.
     */
    @Nullable
    public String getHomePageUrl() {
        return homePageUrl;
    }

    /**
     * Returns the status page URL of this instance.
     */
    @Nullable
    public String getStatusPageUrl() {
        return statusPageUrl;
    }

    /**
     * Returns the health check URL of this instance.
     */
    @Nullable
    public String getHealthCheckUrl() {
        return healthCheckUrl;
    }

    /**
     * Returns the secure health check URL of this instance.
     */
    @Nullable
    public String getSecureHealthCheckUrl() {
        return secureHealthCheckUrl;
    }

    /**
     * Returns the data center information which this instance belongs to.
     */
    public DataCenterInfo getDataCenterInfo() {
        return dataCenterInfo;
    }

    /**
     * Returns the lease information of this instance.
     */
    public LeaseInfo getLeaseInfo() {
        return leaseInfo;
    }

    /**
     * Returns the metadata of this instance.
     */
    public Map<String, String> getMetadata() {
        return metadata;
    }

    /**
     * Returns the last updated timestamp of this instance.
     */
    public long getLastUpdatedTimestamp() {
        return lastUpdatedTimestamp;
    }

    /**
     * Returns the last dirty timestamp of this instance.
     */
    public long getLastDirtyTimestamp() {
        return lastDirtyTimestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof InstanceInfo)) {
            return false;
        }

        final InstanceInfo that = (InstanceInfo) o;
        return Objects.equal(instanceId, that.instanceId) &&
               Objects.equal(hostName, that.hostName) &&
               Objects.equal(appName, that.appName) &&
               Objects.equal(appGroupName, that.appGroupName) &&
               Objects.equal(ipAddr, that.ipAddr) &&
               Objects.equal(vipAddress, that.vipAddress) &&
               Objects.equal(secureVipAddress, that.secureVipAddress) &&
               Objects.equal(port, that.port) &&
               Objects.equal(securePort, that.securePort) &&
               status == that.status &&
               Objects.equal(homePageUrl, that.homePageUrl) &&
               Objects.equal(statusPageUrl, that.statusPageUrl) &&
               Objects.equal(healthCheckUrl, that.healthCheckUrl) &&
               Objects.equal(secureHealthCheckUrl, that.secureHealthCheckUrl) &&
               Objects.equal(dataCenterInfo, that.dataCenterInfo) &&
               Objects.equal(leaseInfo, that.leaseInfo) &&
               Objects.equal(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(instanceId, hostName, appName, appGroupName, ipAddr, vipAddress,
                                secureVipAddress, port, securePort, status,
                                homePageUrl, statusPageUrl, healthCheckUrl, secureHealthCheckUrl,
                                dataCenterInfo, leaseInfo, metadata);
    }

    @Override
    public String toString() {
        return toStringHelper(this).omitNullValues()
                                   .add("instanceId", instanceId)
                                   .add("hostName", hostName)
                                   .add("appName", appName)
                                   .add("appGroupName", appGroupName)
                                   .add("ipAddr", ipAddr)
                                   .add("vipAddress", vipAddress)
                                   .add("secureVipAddress", secureVipAddress)
                                   .add("port", port)
                                   .add("securePort", securePort)
                                   .add("status", status)
                                   .add("homePageUrl", homePageUrl)
                                   .add("statusPageUrl", statusPageUrl)
                                   .add("healthCheckUrl", healthCheckUrl)
                                   .add("secureHealthCheckUrl", secureHealthCheckUrl)
                                   .add("dataCenterInfo", dataCenterInfo)
                                   .add("leaseInfo", leaseInfo)
                                   .add("metadata", metadata)
                                   .add("lastUpdatedTimestamp", lastUpdatedTimestamp)
                                   .add("lastDirtyTimestamp", lastDirtyTimestamp)
                                   .toString();
    }

    /**
     * The status of an {@link InstanceInfo}.
     */
    public enum InstanceStatus {

        UP,
        DOWN,
        STARTING,
        OUT_OF_SERVICE,
        UNKNOWN;

        /**
         * Returns the {@link Enum} value corresponding to the specified {@code str}.
         * {@link #UNKNOWN} is returned if none of {@link Enum}s are matched.
         */
        public static InstanceStatus toEnum(String str) {
            requireNonNull(str, "str");
            try {
                return valueOf(str);
            } catch (IllegalArgumentException e) {
                logger.warn("unknown enum value: {} (expected: {}), {} is set by default. ",
                            str, values(), UNKNOWN);
            }
            return UNKNOWN;
        }
    }

    /**
     * The port information.
     */
    public static class PortWrapper {
        private final boolean enabled;
        private final int port;

        public PortWrapper(@JsonProperty("@enabled") boolean enabled, @JsonProperty("$") int port) {
            this.enabled = enabled;
            this.port = port;
        }

        @JsonProperty("@enabled")
        @JsonSerialize(using = ToStringSerializer.class)
        public boolean isEnabled() {
            return enabled;
        }

        @JsonProperty("$")
        public int getPort() {
            return port;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof PortWrapper)) {
                return false;
            }
            final PortWrapper that = (PortWrapper) obj;
            return enabled == that.enabled && port == that.port;
        }

        @Override
        public int hashCode() {
            return port * 31 + Boolean.hashCode(enabled);
        }

        @Override
        public String toString() {
            return toStringHelper(this).add("enabled", enabled)
                                       .add("port", port)
                                       .toString();
        }
    }
}
