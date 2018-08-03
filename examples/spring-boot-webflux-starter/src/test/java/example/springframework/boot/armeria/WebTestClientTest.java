package example.springframework.boot.armeria;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.linecorp.armeria.spring.web.reactive.ArmeriaClientHttpConnector;

@RunWith(SpringRunner.class)
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "spring.main.web-application-type=reactive"
)
public class WebTestClientTest {

    @RestController
    static class HelloController {
        @GetMapping("/hello")
        String hello() {
            return "Hello, World";
        }
    }

    private WebTestClient webTestClient =
            WebTestClient.bindToServer(new ArmeriaClientHttpConnector()).build();

    @LocalServerPort
    private int port;

    @Test
    public void getHelloWorld() {
        webTestClient.get()
                     .uri(pathToUri("/hello"))
                     .exchange()
                     .expectStatus().isOk()
                     .expectBody(String.class).isEqualTo("Hello, World");
    }

    private String pathToUri(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
