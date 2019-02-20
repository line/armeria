package example.armeria.server.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Server;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

public class MainTest {

    private static Server server;
    private static HttpClient client;

    @BeforeClass
    public static void beforeClass() throws Exception {
        final AtomicLong sequence = new AtomicLong();

        // The server emits only 5 events here because this test is to show how the events are encoded.
        server = Main.newServer(0, 0,
                                Duration.ofMillis(200), 5, () -> Long.toString(sequence.getAndIncrement()));
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
    public void testServerSentEvents() {
        StepVerifier.create(Flux.from(client.get("/long")).log())
                    .expectNext(HttpHeaders.of(HttpStatus.OK).contentType(MediaType.EVENT_STREAM))
                    .expectNext(HttpData.ofUtf8("data:0\n\n"))
                    .expectNext(HttpData.ofUtf8("data:1\n\n"))
                    .expectNext(HttpData.ofUtf8("data:2\n\n"))
                    .expectNext(HttpData.ofUtf8("data:3\n\n"))
                    .expectNext(HttpData.ofUtf8("data:4\n\n"))
                    .assertNext(o -> assertThat(o.isEndOfStream()))
                    .expectComplete()
                    .verify();

        StepVerifier.create(Flux.from(client.get("/short")).log())
                    .expectNext(HttpHeaders.of(HttpStatus.OK).contentType(MediaType.EVENT_STREAM))
                    .expectNext(HttpData.ofUtf8("id:0\ndata:5\nretry:5000\n\n"))
                    .expectNext(HttpData.ofUtf8("id:1\ndata:6\nretry:5000\n\n"))
                    .expectNext(HttpData.ofUtf8("id:2\ndata:7\nretry:5000\n\n"))
                    .expectNext(HttpData.ofUtf8("id:3\ndata:8\nretry:5000\n\n"))
                    .expectNext(HttpData.ofUtf8("id:4\ndata:9\nretry:5000\n\n"))
                    .assertNext(o -> assertThat(o.isEndOfStream()))
                    .expectComplete()
                    .verify();
    }
}
