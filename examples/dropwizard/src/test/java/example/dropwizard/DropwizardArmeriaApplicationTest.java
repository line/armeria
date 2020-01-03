package example.dropwizard;

import static io.dropwizard.testing.ResourceHelpers.resourceFilePath;
import static org.assertj.core.api.Assertions.assertThat;

import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheck.Result;

import com.linecorp.armeria.common.HttpStatus;

import example.dropwizard.health.PingCheck;
import example.dropwizard.resources.JerseyResource;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;

@ExtendWith(DropwizardExtensionsSupport.class)
public class DropwizardArmeriaApplicationTest {
    public static final ResourceExtension RESOURCES = ResourceExtension.builder()
                                                                       .addResource(new JerseyResource())
                                                                       .build();
    private static final DropwizardAppExtension<DropwizardArmeriaConfiguration> EXTENSION =
            new DropwizardAppExtension<>(DropwizardArmeriaApplication.class,
                                         resourceFilePath("test-server.yaml"));
    private static WebTarget client;

    private static String endpoint;

    @BeforeAll
    public static void setUp() {
        endpoint = "http://localhost:" + EXTENSION.getLocalPort();
        client = EXTENSION.client().target(endpoint);
    }

    @Test
    void testPingHealthCheck() {
        final HealthCheck ping = new PingCheck();

        final Result res = ping.execute();

        assertThat(res.isHealthy()).isTrue();
        assertThat(res.getMessage()).isEqualTo("pong");
    }

    @Test
    void testJerseyResource() {
        final Response resp = RESOURCES.target("/jersey").request().get();
        assertThat(resp.getStatus()).isEqualTo(HttpStatus.OK.code());
        assertThat(resp.readEntity(String.class)).isEqualTo("Hello, Jersey!");
    }

    @Test
    void testArmeriaViaClient() {
        final Response resp = client.path("/armeria")
                                    .request()
                                    .buildGet()
                                    .invoke();
        assertThat(resp.getStatus()).isEqualTo(HttpStatus.OK.code());
        assertThat(resp.readEntity(String.class)).isEqualTo("Hello, Armeria!");
    }

    @Test
    void testRootViaClient() {
        final Response resp = client.path("/")
                                    .request()
                                    .buildGet()
                                    .invoke();
        assertThat(resp.getStatus()).isEqualTo(HttpStatus.OK.code());
        assertThat(resp.readEntity(String.class)).isEqualTo("<h2>It works!</h2>");
    }

    @Test
    void testHelloServiceJsonViaClient() {
        final Response resp = client.path("/hello")
                                    .request(MediaType.APPLICATION_JSON_TYPE)
                                    .buildGet()
                                    .invoke();
        assertThat(resp.getStatus()).isEqualTo(HttpStatus.OK.code());
        assertThat(resp.readEntity(String.class)).isEqualTo("{ \"name\": \"Armeria\" }");
    }
}
