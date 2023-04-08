package example.springframework.boot.webflux;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

@ActiveProfiles("testbed")
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
class HelloApplicationIntegrationTest {

    @LocalServerPort
    int port;

    private WebTestClient client;

    @Inject
    private ClientHttpConnector connector;

    @PostConstruct
    void setUp() {
        // Use ArmeriaClientHttpConnector if you want to send an HTTP request to the running
        // Spring Boot application via Armeria HTTP client.
        client = WebTestClient.bindToServer(connector)
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
