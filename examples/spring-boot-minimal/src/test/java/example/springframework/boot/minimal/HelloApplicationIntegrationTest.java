package example.springframework.boot.minimal;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import javax.inject.Inject;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import com.linecorp.armeria.client.AsyncHttpClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;

@RunWith(SpringRunner.class)
@ActiveProfiles("testbed")
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
public class HelloApplicationIntegrationTest {

    @Inject
    private Server server;

    private AsyncHttpClient client;

    @Before
    public void setup() {
        client = AsyncHttpClient.of("http://localhost:" + server.activeLocalPort());
    }

    @Test
    public void success() {
        final AggregatedHttpResponse response = client.get("/hello/Spring").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8())
                .isEqualTo("Hello, Spring! This message is from Armeria annotated service!");
    }

    @Test
    public void failure() {
        final AggregatedHttpResponse response = client.get("/hello/a").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatJson(response.contentUtf8())
                .node("message")
                .isEqualTo("hello.name: name should have between 3 and 10 characters");
    }
}
