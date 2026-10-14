package example.springframework.boot.xds.cloudconfig.client;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;

/**
 * A Spring Boot client application that loads xDS resources from a
 * Spring Cloud Config Server. Properties are loaded from {@code application-client.yml}.
 *
 * <p>Exposes a {@code GET /relay?path=...} endpoint that forwards the request to
 * the upstream resolved by the xDS listener and returns the response.
 */
@SpringBootApplication
@RestController
public class ClientMain {

    private final WebClient xdsWebClient;

    public ClientMain(WebClient xdsWebClient) {
        this.xdsWebClient = xdsWebClient;
    }

    public static void main(String[] args) {
        createApplication().run(args);
    }

    public static SpringApplication createApplication() {
        final SpringApplication app = new SpringApplication(ClientMain.class);
        app.setAdditionalProfiles("client");
        // spring-cloud-config-server on the classpath disables the config client
        // via ConfigServerBootstrapApplicationListener; re-enable it explicitly.
        app.setDefaultProperties(Map.of("spring.cloud.config.enabled", "true"));
        return app;
    }

    @GetMapping("/relay")
    String relay(@RequestParam(defaultValue = "/actuator/health") String path) {
        final AggregatedHttpResponse response = xdsWebClient.get(path).aggregate().join();
        return response.status() + "\n" + response.contentUtf8();
    }
}
