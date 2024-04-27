package example.springframework.boot.jetty;

import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Loader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.web.embedded.jetty.JettyServerCustomizer;
import org.springframework.boot.web.embedded.jetty.JettyServletWebServerFactory;
import org.springframework.boot.web.embedded.jetty.JettyWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.healthcheck.HealthChecker;
import com.linecorp.armeria.server.jetty.JettyService;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;

import jakarta.servlet.Servlet;

/**
 * Configures an Armeria server to redirect the incoming requests to the Jetty instance provided by
 * Spring Boot. It also sets up a {@link HealthChecker} so that it works well with a load balancer.
 */
@Configuration
public class HelloConfiguration {

    /**
     * Returns a new {@link HealthChecker} that marks the server as unhealthy when Tomcat becomes unavailable.
     */
    @Bean
    public HealthChecker jettyHealthChecker(ServletWebServerApplicationContext applicationContext) {
        final Server server = jettyServer(applicationContext).getServer();
        return server::isRunning;
    }

    /**
     * Returns a new {@link JettyService} that redirects the incoming requests to the Jetty instance
     * provided by Spring Boot.
     */
    @Bean
    public JettyService jettyService(ServletWebServerApplicationContext applicationContext) {
        final JettyWebServer jettyWebServer = jettyServer(applicationContext);
        return JettyService.of(jettyWebServer.getServer(), null);
    }

    /**
     * Returns a new {@link ArmeriaServerConfigurator} that is responsible for configuring a {@link Server}
     * using the given {@link ServerBuilder}.
     */
    @Bean
    public ArmeriaServerConfigurator armeriaServiceInitializer(JettyService jettyService) {
        return sb -> sb.serviceUnder("/", jettyService);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({ Servlet.class, Server.class, Loader.class, WebAppContext.class })
    static class EmbeddedJetty {

        @Bean
        JettyServletWebServerFactory jettyServletWebServerFactory(
                ObjectProvider<JettyServerCustomizer> serverCustomizers) {
            final JettyServletWebServerFactory factory = new JettyServletWebServerFactory() {

                @Override
                protected JettyWebServer getJettyWebServer(Server server) {
                    return new JettyWebServer(server, true);
                }
            };
            factory.getServerCustomizers().addAll(serverCustomizers.orderedStream().toList());
            return factory;
        }
    }

    /**
     * Extracts a Jetty {@link Server} from Spring webapp context.
     */
    private static JettyWebServer jettyServer(ServletWebServerApplicationContext applicationContext) {
        return (JettyWebServer) applicationContext.getWebServer();
    }
}
