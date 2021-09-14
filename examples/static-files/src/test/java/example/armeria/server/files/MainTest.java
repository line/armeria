package example.armeria.server.files;

import static example.armeria.server.files.Main.configureServices;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class MainTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            configureServices(sb);
        }
    };

    @Test
    void testFavicon() {
        // Download the favicon.
        final AggregatedHttpResponse res = client().get("/favicon.ico").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().contentType()).isEqualTo(MediaType.parse("image/x-icon"));
    }

    @Test
    void testDirectoryListing() {
        // Download the directory listing.
        final AggregatedHttpResponse res = client().get("/").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.content().toStringUtf8()).contains("Directory listing: /");
    }

    private static WebClient client() {
        return WebClient.of(server.httpUri());
    }
}
