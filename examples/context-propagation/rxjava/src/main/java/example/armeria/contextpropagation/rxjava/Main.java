package example.armeria.contextpropagation.rxjava;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Server;

public class Main {

    public static void main(String[] args) {
        final Server backend = Server.builder()
                                     .service("/square/{num}", (ctx, req) -> {
                                         final long num = Long.parseLong(ctx.pathParam("num"));
                                         return HttpResponse.of(Long.toString(num * num));
                                     })
                                     .http(8081)
                                     .build();

        final WebClient backendClient = WebClient.of("http://localhost:8081");

        final Server frontend =
                Server.builder()
                      .http(8080)
                      .serviceUnder("/", new MainService(backendClient))
                      .build();

        backend.closeOnShutdown();
        frontend.closeOnShutdown();

        backend.start().join();
        frontend.start().join();
    }
}
