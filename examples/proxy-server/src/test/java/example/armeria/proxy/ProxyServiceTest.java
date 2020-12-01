package example.armeria.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ProxyServiceTest {

    @RegisterExtension
    static final ServerExtension backend = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/api", (ctx, req) -> {
                final String forwarded = req.headers().get(HttpHeaderNames.FORWARDED);
                final String message = forwarded != null ? forwarded : "no forwarded header";
                return HttpResponse.of(message);
            });
        }
    };

    @Test
    void proxyServiceAddForwardedHeader() throws Exception {
        final WebClient backendClient = WebClient.of(backend.httpUri());
        final Server server = Server.builder().serviceUnder("/", new ProxyService(backendClient)).build();
        server.start().join();
        final WebClient client =
                WebClient.of("http://127.0.0.1:" + server.activePort().localAddress().getPort());
        assertThat(client.get("/api").aggregate().join().contentUtf8()).contains("host");
        server.stop().join();
    }
}
