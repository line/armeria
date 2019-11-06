package example.dropwizard.health;

import com.codahale.metrics.health.HealthCheck;

public class PingCheck extends HealthCheck {
    @Override
    protected Result check() throws Exception {
        return Result.healthy("pong");
    }
}
