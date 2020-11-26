package example.springframework.boot.tomcat;

import static org.assertj.core.api.Assertions.assertThat;

import javax.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;

@ActiveProfiles("testbed")
@SpringBootTest(
        classes = {
                HelloConfiguration.class,
                HelloController.class
        },
        webEnvironment = WebEnvironment.DEFINED_PORT)
@EnableAutoConfiguration
class HelloIntegrationTest {

    @Inject
    private Server server;
    private WebClient client;

    @BeforeEach
    void initClient() {
        if (client == null) {
            client = WebClient.of("http://127.0.0.1:" + server.activeLocalPort());
        }
    }

    @Test
    void index() {
        final AggregatedHttpResponse res = client.get("/").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("index");
    }

    @Test
    void hello() throws Exception {
        final AggregatedHttpResponse res = client.get("/hello").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("Hello, World");
    }

    @Test
    void healthCheck() throws Exception {
        final AggregatedHttpResponse res = client.get("/internal/healthcheck").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("{\"healthy\":true}");
    }
}
