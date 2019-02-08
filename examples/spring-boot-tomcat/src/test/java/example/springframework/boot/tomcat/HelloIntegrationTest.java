package example.springframework.boot.tomcat;

import static org.assertj.core.api.Assertions.assertThat;

import javax.inject.Inject;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;

@RunWith(SpringRunner.class)
@ActiveProfiles("testbed")
@SpringBootTest(
        classes = {
                HelloConfiguration.class,
                HelloController.class
        },
        webEnvironment = WebEnvironment.DEFINED_PORT)
@EnableAutoConfiguration
public class HelloIntegrationTest {

    @Inject
    private Server server;
    private HttpClient client;

    @Before
    public void initClient() {
        if (client == null) {
            client = HttpClient.of("http://127.0.0.1:" + server.activePort().get().localAddress().getPort());
        }
    }

    @Test
    public void index() {
        final AggregatedHttpMessage res = client.get("/").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("index");
    }

    @Test
    public void hello() throws Exception {
        final AggregatedHttpMessage res = client.get("/hello").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("Hello, World");
    }

    @Test
    public void healthCheck() throws Exception {
        final AggregatedHttpMessage res = client.get("/internal/healthcheck").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("ok");
    }
}
