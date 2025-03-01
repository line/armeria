/*
 * Copyright 2025 LY Corporation
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
package com.linecorp.armeria.common.eureka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.eureka.InstanceInfo.InstanceStatus;
import com.linecorp.armeria.common.eureka.InstanceInfo.PortWrapper;

class InstanceInfoTest {

    @Test
    void instanceInfoShouldReturnAssociatedInstanceInfo() {

        final String instanceId = "123";
        final String appName = "myApp";
        final String appGroupName = "myGroup";
        final String hostName = "myHost";
        final String ipAddr = "192.168.1.1";
        final String vipAddress = "10.0.0.1";
        final String secureVipAddress = "10.0.0.2";
        final PortWrapper port = new PortWrapper(true,80);
        final PortWrapper securePort = new PortWrapper(true,443);
        final InstanceStatus status = InstanceStatus.UP;
        final String homePageUrl = "https://example.com";
        final String statusPageUrl = "https://status.example.com";
        final String healthCheckUrl = "/health";
        final String secureHealthCheckUrl = "/secure/health";
        final DataCenterInfo dataCenterInfo = new DataCenterInfo("name",new HashMap<>());
        final LeaseInfo leaseInfo = new LeaseInfo(30,90);
        final Map<String, String> metadata = new HashMap<>();
        final InstanceInfo instanceInfo = new InstanceInfo(
                instanceId,
                appName,
                appGroupName,
                hostName,
                ipAddr,
                vipAddress,
                secureVipAddress,
                port,
                securePort,
                status,
                homePageUrl,
                statusPageUrl,
                healthCheckUrl,
                secureHealthCheckUrl,
                dataCenterInfo,
                leaseInfo,
                metadata
        );
        final Endpoint endpoint = Endpoint.parse("foo");

        final Endpoint endpointWithInstanceInfo = InstanceInfo.setInstanceInfo(endpoint, instanceInfo);

        final InstanceInfo instanceInfoRetrieved = InstanceInfo.instanceInfo(endpointWithInstanceInfo);
        assertThat(instanceInfoRetrieved).isNotNull();
        assertThat(instanceInfoRetrieved).isSameAs(instanceInfo);
        assertThat(instanceInfoRetrieved.getInstanceId()).isEqualTo(instanceId);
        assertThat(instanceInfoRetrieved.getAppName()).isEqualTo(appName);
        assertThat(instanceInfoRetrieved.getAppGroupName()).isEqualTo(appGroupName);
        assertThat(instanceInfoRetrieved.getHostName()).isEqualTo(hostName);
        assertThat(instanceInfoRetrieved.getIpAddr()).isEqualTo(ipAddr);
        assertThat(instanceInfoRetrieved.getVipAddress()).isEqualTo(vipAddress);
        assertThat(instanceInfoRetrieved.getSecureVipAddress()).isEqualTo(secureVipAddress);
        assertThat(instanceInfoRetrieved.getPort()).isEqualTo(port);
        assertThat(instanceInfoRetrieved.getSecurePort()).isEqualTo(securePort);
        assertThat(instanceInfoRetrieved.getStatus()).isEqualTo(status);
        assertThat(instanceInfoRetrieved.getHomePageUrl()).isEqualTo(homePageUrl);
        assertThat(instanceInfoRetrieved.getStatusPageUrl()).isEqualTo(statusPageUrl);
        assertThat(instanceInfoRetrieved.getHealthCheckUrl()).isEqualTo(healthCheckUrl);
        assertThat(instanceInfoRetrieved.getSecureHealthCheckUrl()).isEqualTo(secureHealthCheckUrl);
        assertThat(instanceInfoRetrieved.getDataCenterInfo()).isEqualTo(dataCenterInfo);
        assertThat(instanceInfoRetrieved.getLeaseInfo()).isEqualTo(leaseInfo);
        assertThat(instanceInfoRetrieved.getMetadata()).isEqualTo(metadata);
    }
}
