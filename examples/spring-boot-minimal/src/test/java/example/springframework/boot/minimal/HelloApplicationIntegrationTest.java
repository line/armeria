package example.springframework.boot.minimal;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.junit4.SpringRunner;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpStatus;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
public class HelloApplicationIntegrationTest {

    private final HttpClient client = HttpClient.of("http://localhost:8080");

    @Test
    public void success() {
        final AggregatedHttpMessage response = client.get("/hello/Spring").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8())
                .isEqualTo("Hello, Spring! This message is from Armeria annotated service!");
    }

    @Test
    public void failure() {
        final AggregatedHttpMessage response = client.get("/hello/a").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatJson(response.contentUtf8())
                .node("message")
                .isEqualTo("hello.name: name should have between 3 and 10 characters");
    }
}
