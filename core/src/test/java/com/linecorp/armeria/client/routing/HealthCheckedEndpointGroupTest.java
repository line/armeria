package com.linecorp.armeria.client.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.routing.endpoint.healthcheck.HealthCheckedEndpointGroup;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.http.healthcheck.HttpHealthCheckService;

import io.netty.util.internal.PlatformDependent;

public class HealthCheckedEndpointGroupTest {

    private static class ServiceServer {
        private Server server;
        private final String healthCheckPath;
        private final int port;

        ServiceServer(String healthCheckPath, int port) {
            this.healthCheckPath = healthCheckPath;
            this.port = port;
        }

        private void configureServer() throws Exception {
            server = new ServerBuilder()
                    .serviceAt(healthCheckPath, new HttpHealthCheckService())
                    .port(port, SessionProtocol.HTTP)
                    .build();

            try {
                server.start().get();
            } catch (InterruptedException e) {
                PlatformDependent.throwException(e);
            }
        }

        public ServiceServer start() throws Exception {
            configureServer();
            return this;
        }

        public void stop() {
            server.stop();
        }
    }

    @Test
    public void endpoints() throws Exception {
        String healthCheckPath = "/healthcheck";
        HealthCheckedEndpointGroup endpointGroup = new HealthCheckedEndpointGroup(
                ClientFactory.DEFAULT,
                new StaticEndpointGroup(
                        Endpoint.of("127.0.0.1", 1234),
                        Endpoint.of("127.0.0.1", 2345)),
                healthCheckPath);
        assertThat(endpointGroup.endpoints()).isEmpty();

        ServiceServer serverOne = new ServiceServer(healthCheckPath, 1234).start();
        ServiceServer serverTwo = new ServiceServer(healthCheckPath, 2345).start();

        Thread.sleep(4000); // Wait until updating server list.
        assertThat(endpointGroup.endpoints()).containsExactly(
                Endpoint.of("127.0.0.1", 1234),
                Endpoint.of("127.0.0.1", 2345));

        serverOne.stop();
        serverTwo.stop();
    }

    @Test
    public void endpoints_containsUnhealthyServer() throws Exception {
        String healthCheckPath = "/healthcheck";
        ServiceServer serverOne = new ServiceServer(healthCheckPath, 1234).start();

        HealthCheckedEndpointGroup endpointGroup = new HealthCheckedEndpointGroup(
                ClientFactory.DEFAULT,
                new StaticEndpointGroup(
                        Endpoint.of("127.0.0.1", 1234),
                        Endpoint.of("127.0.0.1", 2345)),
                healthCheckPath);
        Thread.sleep(4000); // Wait until updating server list.

        assertThat(endpointGroup.endpoints()).containsOnly(Endpoint.of("127.0.0.1", 1234));
        serverOne.stop();
    }
}
