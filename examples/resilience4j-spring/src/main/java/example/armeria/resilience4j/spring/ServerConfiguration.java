package example.armeria.resilience4j.spring;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.prometheus.PrometheusMeterRegistries;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

@Configuration
public class ServerConfiguration {
    @Bean
    public ArmeriaServerConfigurator armeriaServerConfigurator() {
        return b -> {
            b.service("/200", (ctx, req) -> HttpResponse.of(200));
            b.service("/500-1", (ctx, req) -> HttpResponse.of(500));
            b.service("/500-2", (ctx, req) -> HttpResponse.of(500));
        };
    }

    @Bean
    public SimpleMeterRegistry simpleMeterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    public PrometheusMeterRegistry prometheusMeterRegistry() {
        return PrometheusMeterRegistries.newRegistry();
    }

    @Bean
    public MeterRegistry meterRegistry(SimpleMeterRegistry simpleMeterRegistry,
                                       PrometheusMeterRegistry prometheusMeterRegistry) {
        final CompositeMeterRegistry meterRegistry = new CompositeMeterRegistry();
        meterRegistry.add(simpleMeterRegistry);
        meterRegistry.add(prometheusMeterRegistry);
        return meterRegistry;
    }
}
