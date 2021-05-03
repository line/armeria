package example.armeria.server.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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

    static final AtomicLong sequence = new AtomicLong();

    @BeforeAll
    static void beforeClass() throws Exception {

        // The server emits only 5 events here because this test is to show how the events are encoded.
        server = Main.newServer(0, 0,
                                Duration.ofMillis(100), 5, () -> Long.toString(sequence.getAndIncrement()));
        server.start().join();
        client = WebClient.builder("http://127.0.0.1:" + server.activeLocalPort())
                          .responseTimeout(Duration.ofSeconds(15))
                          .build();
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

    @AfterEach
    void afterEach() {
        sequence.set(0);
    }

    @Test
    void testServerSentEventsLong() {
        StepVerifier.create(Flux.from(client.get("/long")).log())
                    .expectNext(ResponseHeaders.of(HttpStatus.OK,
                                                   HttpHeaderNames.CONTENT_TYPE, MediaType.EVENT_STREAM))
                    .expectNext(HttpData.ofUtf8("data:0\n\n"))
                    .expectNext(HttpData.ofUtf8("data:1\n\n"))
                    .expectNext(HttpData.ofUtf8("data:2\n\n"))
                    .expectNext(HttpData.ofUtf8("data:3\n\n"))
                    .expectNext(HttpData.ofUtf8("data:4\n\n"))
                    .assertNext(o -> assertThat(o.isEndOfStream()).isTrue())
                    .expectComplete()
                    .verify();
    }

    @Test
    void testServerSentEventsShort() {
        StepVerifier.create(Flux.from(client.get("/short")).log())
                    .expectNext(ResponseHeaders.of(HttpStatus.OK,
                                                   HttpHeaderNames.CONTENT_TYPE, MediaType.EVENT_STREAM))
                    .expectNext(HttpData.ofUtf8("id:0\ndata:0\nretry:5000\n\n"))
                    .expectNext(HttpData.ofUtf8("id:1\ndata:1\nretry:5000\n\n"))
                    .expectNext(HttpData.ofUtf8("id:2\ndata:2\nretry:5000\n\n"))
                    .expectNext(HttpData.ofUtf8("id:3\ndata:3\nretry:5000\n\n"))
                    .expectNext(HttpData.ofUtf8("id:4\ndata:4\nretry:5000\n\n"))
                    .assertNext(o -> assertThat(o.isEndOfStream()).isTrue())
                    .expectComplete()
                    .verify();
    }
}
