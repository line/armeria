package example.springframework.boot.webflux;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.linecorp.armeria.spring.web.reactive.ArmeriaClientAutoConfiguration;

@WebFluxTest(HelloController.class)
@ImportAutoConfiguration(ArmeriaClientAutoConfiguration.class)
class HelloControllerTest {

    @Inject
    HelloController controller;

    @Test
    void getHelloWorld() {
        WebTestClient.bindToController(controller)
                     .build()
                     .get()
                     .uri("/hello")
                     .exchange()
                     .expectStatus().isOk()
                     .expectBody(String.class).isEqualTo("Hello, World");
    }
}
