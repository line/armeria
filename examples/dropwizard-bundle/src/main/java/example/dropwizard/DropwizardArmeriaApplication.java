package example.dropwizard;

import java.util.Objects;

import javax.validation.Valid;

import example.dropwizard.armeria.services.http.HelloService;
import example.dropwizard.health.PingCheck;
import example.dropwizard.resources.JerseyResource;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;

import com.linecorp.armeria.server.dropwizard.ArmeriaBundle;
import io.dropwizard.Application;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

public class DropwizardArmeriaApplication extends Application<DropwizardArmeriaConfiguration> {

    public static void main(final String[] args) throws Exception {
        new DropwizardArmeriaApplication().run(args);
    }

    @Override
    public String getName() {
        return "dropwizard-armeria";
    }

    @Override
    public void initialize(final Bootstrap<DropwizardArmeriaConfiguration> bootstrap) {
        // TODO: application initialization
        final ArmeriaBundle bundle = new ArmeriaBundle<DropwizardArmeriaConfiguration>() {
            @Override
            public void onServerBuilderReady(final ServerBuilder builder) {
                Objects.requireNonNull(builder);

                builder.service("/", (ctx, res) -> HttpResponse.of(MediaType.HTML_UTF_8, "<h2>It works!</h2>"));
                builder.service("/armeria", (ctx, res) -> HttpResponse.of("Hello, Armeria!"));

                builder.annotatedService(new HelloService());

                // TODO: Hook up a Thrift service
                // TODO: Hook up a gRPC service
            }
        };
        bootstrap.addBundle(bundle);
    }

    @Override
    public void run(@Valid final DropwizardArmeriaConfiguration configuration,
                    final Environment environment) {
        // TODO: implement application

        environment.jersey().register(JerseyResource.class);

        environment.healthChecks().register("ping", new PingCheck());
    }
}
