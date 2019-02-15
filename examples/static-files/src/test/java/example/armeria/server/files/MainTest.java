package example.armeria.server.files;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Server;

public class MainTest {

    private static Server server;
    private static HttpClient client;

    @BeforeClass
    public static void beforeClass() throws Exception {
        server = Main.newServer(0, 0);
        server.start().join();
        client = HttpClient.of("http://127.0.0.1:" + server.activePort().get().localAddress().getPort());
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
        final AggregatedHttpMessage res = client.get("/favicon.ico").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().contentType()).isEqualTo(MediaType.parse("image/x-icon"));
    }

    @Test
    public void testDirectoryListing() {
        // Download the directory listing.
        final AggregatedHttpMessage res = client.get("/").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.content().toStringUtf8()).contains("Directory listing: /");
    }
}
