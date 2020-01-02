package example.armeria.server.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.Server;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class MainTest {

    private static Server server;
    private static WebClient client;

    @BeforeAll
    static void beforeClass() throws Exception {
        final AtomicLong sequence = new AtomicLong();

        // The server emits only 5 events here because this test is to show how the events are encoded.
        server = Main.newServer(0, 0,
                                Duration.ofMillis(200), 5, () -> Long.toString(sequence.getAndIncrement()));
        server.start().join();
        client = WebClient.of("http://127.0.0.1:" + server.activeLocalPort());
    }

    @AfterAll
    static void afterClass() {
        if (server != null) {
            server.stop().join();
        }
        if (client != null) {
            client.factory().close();
        }
    }

    @Test
    void testServerSentEvents() {
        StepVerifier.create(Flux.from(client.get("/long")).log())
                    .expectNext(ResponseHeaders.of(HttpStatus.OK,
                                                   HttpHeaderNames.CONTENT_TYPE, MediaType.EVENT_STREAM))
                    .expectNext(HttpData.ofUtf8("data:0\n\n"))
                    .expectNext(HttpData.ofUtf8("data:1\n\n"))
                    .expectNext(HttpData.ofUtf8("data:2\n\n"))
                    .expectNext(HttpData.ofUtf8("data:3\n\n"))
                    .expectNext(HttpData.ofUtf8("data:4\n\n"))
                    .assertNext(o -> assertThat(o.isEndOfStream()))
                    .expectComplete()
                    .verify();

        StepVerifier.create(Flux.from(client.get("/short")).log())
                    .expectNext(ResponseHeaders.of(HttpStatus.OK,
                                                   HttpHeaderNames.CONTENT_TYPE, MediaType.EVENT_STREAM))
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
