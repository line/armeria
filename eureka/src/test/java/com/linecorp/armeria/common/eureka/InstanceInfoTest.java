package com.linecorp.armeria.common.eureka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.eureka.InstanceInfo.InstanceStatus;
import com.linecorp.armeria.common.eureka.InstanceInfo.PortWrapper;
import com.linecorp.armeria.internal.common.eureka.DataCenterInfo;
import com.linecorp.armeria.internal.common.eureka.LeaseInfo;

class InstanceInfoTest {

    @Test
    void getShouldReturnAssociatedInstanceInfo() {

        final String instanceId = "123";
        final String appName = "myApp";
        final String appGroupName = "myGroup";
        final String hostName = "myHost";
        final String ipAddr = "192.168.1.1";
        final String vipAddress = "10.0.0.1";
        final String secureVipAddress = "10.0.0.2";
        final PortWrapper port = new PortWrapper(true,80);
        final PortWrapper securePort = new PortWrapper(true,443);
        final InstanceInfo. InstanceStatus status = InstanceStatus.UP;
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

        final Endpoint endpointWith = InstanceInfo.with(endpoint, instanceInfo);

        final InstanceInfo instanceInfoRetrieved = InstanceInfo.get(endpointWith);
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
