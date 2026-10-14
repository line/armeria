package example.springframework.boot.xds.cloudconfig.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

import com.linecorp.armeria.spring.xds.SpringXdsAutoConfiguration;

/**
 * A Spring Cloud Config Server that serves xDS cluster definitions from a native
 * (file-based) repository.
 */
@EnableConfigServer
@SpringBootApplication(exclude = SpringXdsAutoConfiguration.class)
public class ConfigServerMain {

    public static void main(String[] args) {
        createApplication().run(args);
    }

    public static SpringApplication createApplication() {
        final SpringApplication app = new SpringApplication(ConfigServerMain.class);
        app.setAdditionalProfiles("server", "native");
        return app;
    }
}
