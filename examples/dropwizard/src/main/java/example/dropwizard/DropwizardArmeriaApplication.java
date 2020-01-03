package example.dropwizard;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.dropwizard.ArmeriaBundle;
import com.linecorp.armeria.server.ServerBuilder;

import example.dropwizard.armeria.services.http.HelloService;
import example.dropwizard.health.PingCheck;
import example.dropwizard.resources.JerseyResource;
import io.dropwizard.Application;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

public class DropwizardArmeriaApplication extends Application<DropwizardArmeriaConfiguration> {

    public static void main(String[] args) throws Exception {
        new DropwizardArmeriaApplication().run(args);
    }

    @Override
    public String getName() {
        return "dropwizard-armeria";
    }

    @Override
    public void initialize(Bootstrap<DropwizardArmeriaConfiguration> bootstrap) {
        final ArmeriaBundle<DropwizardArmeriaConfiguration> bundle =
                new ArmeriaBundle<DropwizardArmeriaConfiguration>() {
            @Override
            public void configure(ServerBuilder builder) {
                builder.service("/", (ctx, res) -> HttpResponse.of(MediaType.HTML_UTF_8, "<h2>It works!</h2>"));
                builder.service("/armeria", (ctx, res) -> HttpResponse.of("Hello, Armeria!"));

                builder.annotatedService(new HelloService());

                // You can also bind asynchronous RPC services such as Thrift and gRPC:
                // builder.service(THttpService.of(...));
                // builder.service(GrpcService.builder()...build());
            }
        };
        bootstrap.addBundle(bundle);
    }

    @Override
    public void run(DropwizardArmeriaConfiguration configuration,
                    Environment environment) {
        environment.jersey().register(JerseyResource.class);

        environment.healthChecks().register("ping", new PingCheck());
    }
}
