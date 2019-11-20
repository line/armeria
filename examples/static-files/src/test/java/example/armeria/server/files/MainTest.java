package example.armeria.server.files;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Server;

public class MainTest {

    private static Server server;
    private static WebClient client;

    @BeforeClass
    public static void beforeClass() throws Exception {
        server = Main.newServer(0, 0);
        server.start().join();
        client = WebClient.of("http://127.0.0.1:" + server.activeLocalPort());
    }

    @AfterClass
    public static void afterClass() {
        if (server != null) {
            server.stop().join();
        }
        if (client != null) {
            client.factory().close();
        }
    }

    @Test
    public void testFavicon() {
        // Download the favicon.
        final AggregatedHttpResponse res = client.get("/favicon.ico").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().contentType()).isEqualTo(MediaType.parse("image/x-icon"));
    }

    @Test
    public void testDirectoryListing() {
        // Download the directory listing.
        final AggregatedHttpResponse res = client.get("/").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.content().toStringUtf8()).contains("Directory listing: /");
    }
}
