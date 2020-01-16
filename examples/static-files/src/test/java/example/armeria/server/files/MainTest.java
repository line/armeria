package example.armeria.server.files;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Server;

class MainTest {

    private static Server server;
    private static WebClient client;

    @BeforeAll
    static void beforeClass() throws Exception {
        server = Main.newServer(0, 0);
        server.start().join();
        client = WebClient.of("http://127.0.0.1:" + server.activeLocalPort());
    }

    @AfterAll
    static void afterClass() {
        if (server != null) {
            server.stop().join();
        }
        if (client != null) {
            client.options().factory().close();
        }
    }

    @Test
    void testFavicon() {
        // Download the favicon.
        final AggregatedHttpResponse res = client.get("/favicon.ico").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().contentType()).isEqualTo(MediaType.parse("image/x-icon"));
    }

    @Test
    void testDirectoryListing() {
        // Download the directory listing.
        final AggregatedHttpResponse res = client.get("/").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.content().toStringUtf8()).contains("Directory listing: /");
    }
}
