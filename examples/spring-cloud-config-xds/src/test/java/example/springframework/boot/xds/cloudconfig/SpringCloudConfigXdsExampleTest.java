package example.springframework.boot.xds.cloudconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;

import example.springframework.boot.xds.cloudconfig.client.ClientMain;
import example.springframework.boot.xds.cloudconfig.server.ConfigServerMain;

@SpringBootTest(
        classes = ClientMain.class,
        properties = {
                "spring.config.import=configserver:",
                "spring.cloud.config.enabled=true"
        })
class SpringCloudConfigXdsExampleTest {

    private static ConfigurableApplicationContext server;
    private static int configPort;

    @BeforeAll
    static void startConfigServer() {
        server = ConfigServerMain.createApplication().run("--server.port=0");

        configPort = ((WebServerApplicationContext) server).getWebServer().getPort();
        System.setProperty("spring.cloud.config.uri", "http://localhost:" + configPort);
    }

    @AfterAll
    static void stopConfigServer() {
        System.clearProperty("spring.cloud.config.uri");
        if (server != null) {
            server.close();
        }
    }

    @Autowired
    WebClient xdsWebClient;

    @Autowired
    XdsBootstrap xdsBootstrap;

    @Autowired
    ConfigurableEnvironment environment;

    @Autowired
    ApplicationContext applicationContext;

    @Test
    void webClientViaXdsPreprocessor() {
        // Verify the initial cluster snapshot loaded from config server (port 8888)
        final AtomicReference<ClusterSnapshot> snapshotRef = new AtomicReference<>();
        xdsBootstrap.clusterRoot("test-cluster")
                    .addSnapshotWatcher((snapshot, error) -> {
                        if (snapshot != null) {
                            snapshotRef.set(snapshot);
                        }
                    });
        await().untilAsserted(() -> {
            assertThat(snapshotRef.get()).isNotNull();
            assertThat(endpointPort(snapshotRef.get())).isEqualTo(8888);
        });

        // Override the cluster endpoint to point at the config server's actual port
        environment.getPropertySources()
                   .addFirst(new MapPropertySource("test",
                           Map.of("armeria.xds.cluster.test-cluster", clusterYaml(configPort))));
        applicationContext.publishEvent(
                new EnvironmentChangeEvent(Set.of("armeria.xds.cluster.test-cluster")));

        // Wait for the xDS update to propagate
        await().untilAsserted(() ->
                assertThat(endpointPort(snapshotRef.get())).isEqualTo(configPort));

        // Call the config server's actuator health endpoint via the xDS-resolved client
        final AggregatedHttpResponse response =
                xdsWebClient.get("/actuator/health").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }

    private static int endpointPort(ClusterSnapshot snapshot) {
        return snapshot.xdsResource().resource()
                       .getLoadAssignment()
                       .getEndpoints(0)
                       .getLbEndpoints(0)
                       .getEndpoint()
                       .getAddress()
                       .getSocketAddress()
                       .getPortValue();
    }

    private static String clusterYaml(int port) {
        return """
                name: test-cluster
                type: STATIC
                load_assignment:
                  cluster_name: test-cluster
                  endpoints:
                    - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: %d
                """.formatted(port);
    }
}
