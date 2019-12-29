package example.springframework.boot.webflux;

import javax.annotation.PostConstruct;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.linecorp.armeria.spring.web.reactive.ArmeriaClientHttpConnector;

@ActiveProfiles("testbed")
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
class HelloApplicationIntegrationTest {

    @LocalServerPort
    int port;

    private WebTestClient client;

    @PostConstruct
    void setUp() {
        // Use ArmeriaClientHttpConnector if you want to send an HTTP request to the running
        // Spring Boot application via Armeria HTTP client.
        client = WebTestClient.bindToServer(new ArmeriaClientHttpConnector())
                              .baseUrl("http://127.0.0.1:" + port)
                              .build();
    }

    @Test
    void helloWorld() {
        client.get()
              .uri("/hello")
              .exchange()
              .expectStatus().isOk()
              .expectBody(String.class).isEqualTo("Hello, World");
    }
}
