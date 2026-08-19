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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.function.Consumer;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.jayway.jsonpath.JsonPath;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.it.istio.testing.EnabledIfDockerAvailable;
import com.linecorp.armeria.it.istio.testing.IstioClusterExtension;
import com.linecorp.armeria.it.istio.testing.IstioPodTest;
import com.linecorp.armeria.it.istio.testing.IstioServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;

@EnabledIfDockerAvailable
class XdsClientToServerTest {

    private static final int SERVER_PORT = 8080;

    @RegisterExtension
    @Order(1)
    static IstioClusterExtension cluster = new IstioClusterExtension();

    @RegisterExtension
    @Order(2)
    static IstioServerExtension server =
            new IstioServerExtension("xds-echo-server", SERVER_PORT, XdsEchoConfigurator.class,
                                     new XdsServerDeploymentCustomizer());

    /**
     * Customizes the server deployment so the Armeria server handles xDS natively
     * instead of relying on sidecar iptables interception for inbound traffic.
     */
    private static class XdsServerDeploymentCustomizer implements Consumer<DeploymentBuilder> {
        @Override
        public void accept(DeploymentBuilder builder) {
            builder.editSpec()
                   .editTemplate()
                   .editMetadata()
                   .addToAnnotations("traffic.sidecar.istio.io/excludeInboundPorts",
                                     String.valueOf(SERVER_PORT))
                   .addToAnnotations("proxy.istio.io/config",
                                     "{\"proxyMetadata\":{\"ISTIO_DELTA_XDS\":\"false\"}}")
                   .endMetadata()
                   .editSpec()
                   // Declare the shared volumes ourselves. The Istio webhook normally
                   // injects these when mutating pods, but deployments are validated
                   // before any pod is created, so the volumes must already exist in
                   // the template.
                   .addNewVolume()
                   .withName("workload-socket")
                   .withNewEmptyDir().endEmptyDir()
                   .endVolume()
                   .addNewVolume()
                   .withName("istio-envoy")
                   .withNewEmptyDir().endEmptyDir()
                   .endVolume()
                   .editMatchingContainer(c -> "server".equals(c.getName()))
                   .addNewVolumeMount()
                   .withName("workload-socket")
                   .withMountPath("/var/run/secrets/workload-spiffe-uds")
                   .endVolumeMount()
                   .addNewVolumeMount()
                   .withName("istio-envoy")
                   .withMountPath("/etc/istio/proxy")
                   .endVolumeMount()
                   .endContainer()
                   .endSpec()
                   .endTemplate()
                   .endSpec();
        }
    }

    @IstioPodTest
    void serverRequest() throws Exception {
        final String serviceIp = InetAddress.getByName(
                server.serviceName() + ".default.svc.cluster.local").getHostAddress();
        final String listenerName = serviceIp + '_' + server.port();

        final String bootstrapJson = loadBootstrapJson();
        final Bootstrap bootstrap = XdsResourceReader.fromJson(bootstrapJson, Bootstrap.class);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener(listenerName, xdsBootstrap)) {
            final WebClient client = WebClient.builder(preprocessor)
                                              .decorator(LoggingClient.newDecorator())
                                              .build();
            final ClientRequestContext ctx;
            final String responseBody;
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                final AggregatedHttpResponse response = client.get("/echo").aggregate().join();
                ctx = captor.get();
                responseBody = response.contentUtf8();
                assertThat(response.status()).isEqualTo(HttpStatus.OK);
            }

            // Verify that the client and server see each other directly —
            // no sidecar proxy rewrote the connection endpoints.
            final InetSocketAddress clientRemote = ctx.remoteAddress();
            final InetSocketAddress clientLocal = ctx.localAddress();
            assertThat(clientRemote).isNotNull();
            assertThat(clientLocal).isNotNull();

            final String serverRemoteIp = JsonPath.read(responseBody, "$.remoteIp");
            final int serverRemotePort = JsonPath.read(responseBody, "$.remotePort");
            final String serverLocalIp = JsonPath.read(responseBody, "$.localIp");
            final int serverLocalPort = JsonPath.read(responseBody, "$.localPort");

            // client's remote == server's local
            assertThat(clientRemote.getAddress().getHostAddress()).isEqualTo(serverLocalIp);
            assertThat(clientRemote.getPort()).isEqualTo(serverLocalPort);
            // client's local == server's remote
            assertThat(clientLocal.getAddress().getHostAddress()).isEqualTo(serverRemoteIp);
            assertThat(clientLocal.getPort()).isEqualTo(serverRemotePort);
        }
    }

    private static String loadBootstrapJson() {
        return XdsResourceReader.readBootstrap();
    }
}
