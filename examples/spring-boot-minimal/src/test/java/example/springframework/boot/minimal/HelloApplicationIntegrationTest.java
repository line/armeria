package example.springframework.boot.minimal;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import javax.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
class HelloApplicationIntegrationTest {

    @Inject
    private Server server;

    private WebClient client;

    @BeforeEach
    void setUp() {
        client = WebClient.of("http://localhost:" + server.activeLocalPort());
    }

    @Test
    void success() {
        final AggregatedHttpResponse response = client.get("/hello/Spring").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8())
                .isEqualTo("Hello, Spring! This message is from Armeria annotated service!");
    }

    @Test
    void failure() {
        final AggregatedHttpResponse response = client.get("/hello/a").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatJson(response.contentUtf8())
                .node("message")
                .isEqualTo("hello.name: name should have between 3 and 10 characters");
    }
}
