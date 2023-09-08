package example.armeria.resilience4j.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import io.github.resilience4j.circuitbreaker.autoconfigure.CircuitBreakerProperties;

@SpringBootApplication
@EnableConfigurationProperties(CircuitBreakerProperties.class)
public class Main {
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
