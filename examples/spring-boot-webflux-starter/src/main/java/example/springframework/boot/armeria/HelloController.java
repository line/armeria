package example.springframework.boot.armeria;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.Builder;

import com.linecorp.armeria.spring.web.reactive.ArmeriaClientHttpConnector;

import reactor.core.publisher.Mono;

/**
 * An example of a controller which uses {@link WebClient} inside.
 */
@RestController
public class HelloController {

    private final WebClient webClient;

    /**
     * The given {@link Builder} has been configured to have an {@link ArmeriaClientHttpConnector} as
     * its client connector.
     */
    public HelloController(WebClient.Builder builder,
                           @Value("${server.port}") int port) {
        webClient = builder.baseUrl("http://127.0.0.1:" + port).build();
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
