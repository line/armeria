package example.dropwizard;

import static io.dropwizard.testing.ResourceHelpers.resourceFilePath;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutionException;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheck.Result;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;

import example.dropwizard.armeria.services.http.HelloService;
import example.dropwizard.health.PingCheck;
import example.dropwizard.resources.JerseyResource;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;

@ExtendWith(DropwizardExtensionsSupport.class)
public class DropwizardArmeriaApplicationTest {
    public static final ResourceExtension RESOURCES =
            ResourceExtension.builder()
                             .addResource(new JerseyResource())
                             .build();
    private static final DropwizardAppExtension<DropwizardArmeriaConfiguration> EXTENSION =
            new DropwizardAppExtension<>(DropwizardArmeriaApplication.class,
                                         resourceFilePath("test-server.yaml"));
    private static final ObjectMapper OBJECT_MAPPER = Jackson.newObjectMapper();
    private static final Client CLIENT = ClientBuilder.newClient();

    private static HelloService service;
    private static String endpoint;

    @BeforeAll
    void setUp() {
        // endpoint = "http://localhost:" + EXTENSION.getLocalPort();
        service = new HelloService();
    }

    @Test
    void testPingHealthCheck() {
        final HealthCheck ping = new PingCheck();

        final Result res = ping.execute();

        assertThat(res.isHealthy()).isTrue();
        assertThat(res.getMessage()).isEqualTo("pong");
    }

    @Test
    void testJersey() {
        final String content = RESOURCES.target("/jersey").request().get(String.class);
        assertThat(content).isEqualTo("Hello, Jersey!");
    }

    @Test
    void testArmeria_plainText() {
        final String res = service.helloText();
        assertThat(res).isEqualTo("Armeria");
    }

    @Test
    void testArmeria_JSON() throws ExecutionException, InterruptedException {
        // When
        final HttpResponse res = service.helloJson();

        // Then
        final AggregatedHttpResponse aggregatedHttpResponse = res.aggregate().get();
        assertThat(aggregatedHttpResponse.status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregatedHttpResponse.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(aggregatedHttpResponse.contentUtf8()).isEqualTo("{ \"name\": \"Armeria\" }");
    }
}
