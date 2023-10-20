package example.springframework.boot.webflux;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.Builder;

import jakarta.inject.Inject;
import reactor.core.publisher.Mono;

/**
 * An example of a controller which uses {@link WebClient} inside.
 */
@RestController
public class HelloController {

    private final WebClient webClient;

    /**
     * The given {@link Builder} has been configured to have an {@code ArmeriaClientHttpConnector} as
     * its client connector.
     */
    @Inject
    public HelloController(Builder builder,
                           @Value("${server.port}") int port) {
        this(builder.baseUrl("https://127.0.0.1:" + port).build());
    }

    HelloController(WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Returns a string which is retrieved from {@code /hello} using the {@link WebClient}.
     */
    @GetMapping("/")
    Mono<String> index() {
        return webClient.get()
                        .uri("/hello")
                        .retrieve()
                        .bodyToMono(String.class);
    }

    @GetMapping("/hello")
    String hello() {
        return "Hello, World";
    }
}
