package example.armeria.server.auth;

import static com.linecorp.armeria.common.HttpHeaderNames.AUTHORIZATION;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthService;
import com.linecorp.armeria.server.auth.AuthServiceBuilder;
import com.linecorp.armeria.server.auth.Authorizer;

public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        final ServerBuilder sb = Server.builder();
        sb.http(8089);

        //The Hello Word services
        sb.service("/", new AbstractHttpService() {
            @Override
            protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                return HttpResponse.of("Hello,word");
            }
        });

        // Auth with AuthServices Decorator
        final HttpService service = new AbstractHttpService() {
            @Override
            protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                return HttpResponse.of(HttpStatus.OK);
            }
        };
        final Authorizer<HttpRequest> authorizer = (ctx, req) ->
                CompletableFuture.supplyAsync(
                        () -> "token".equals(req.headers().get(AUTHORIZATION)));
        sb.service("/hello",
                service.decorate(AuthService.newDecorator(authorizer)));

        //MyAuthHandler
        final AuthServiceBuilder authServiceBuilder = AuthService.builder();
        authServiceBuilder.add(new MyAuthHandler());
        final AuthService authService = authServiceBuilder.build(new AbstractHttpService() {
            @Override
            protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                final String name = ctx.pathParam("name");
                return HttpResponse.of("Hello, %s!", name);
            }
        });
        sb.service("/greet/{name}",authService);

        //auth1a
        final AuthServiceBuilder auth1aServiceBuilder = AuthService.builder();
        auth1aServiceBuilder.add(new Auth1aHandler());
        final AuthService auth1aService = auth1aServiceBuilder.build(new AbstractHttpService() {
            @Override
            protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                final String name = ctx.pathParam("name");
                return HttpResponse.of("Hello, %s!", name);
            }
        });
        sb.service("/auth1a/{name}",auth1aService);

        //auth2
        final AuthServiceBuilder auth2ServiceBuilder = AuthService.builder();
        auth2ServiceBuilder.add(new Auth2Handler());
        final AuthService auth2Service = auth2ServiceBuilder.build(new AbstractHttpService() {
            @Override
            protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                final String name = ctx.pathParam("name");
                return HttpResponse.of("Hello, %s!", name);
            }
        });
        sb.service("/auth2/{name}",auth2Service);

        final Server server = sb.build();
        final CompletableFuture<Void> future = server.start();
        future.join();
    }
}
