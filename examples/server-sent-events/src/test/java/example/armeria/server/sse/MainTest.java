package example.armeria.server.sse;

import static example.armeria.server.sse.Main.configureServices;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.stream.HttpDecoder;
import com.linecorp.armeria.common.stream.HttpDecoderInput;
import com.linecorp.armeria.common.stream.HttpDecoderOutput;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.buffer.ByteBuf;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class MainTest {

    private static final AtomicLong sequence = new AtomicLong();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            configureServices(sb, Duration.ofMillis(200), 5, () -> Long.toString(sequence.getAndIncrement()));
        }
    };

    @AfterEach
    void afterEach() {
        sequence.set(0);
    }

    @Test
    void testServerSentEventsLong() {
        final HttpResponse response = client().get("/long");
        final StreamMessage<String> decoded = response.decode(new SimpleServerSentMessageDecoder());
        StepVerifier.create(Flux.from(decoded).log())
                    .expectNext("data:0")
                    .expectNext("data:1")
                    .expectNext("data:2")
                    .expectNext("data:3")
                    .expectNext("data:4")
                    .expectComplete()
                    .verify();
    }

    @Test
    void testServerSentEventsShort() {
        final HttpResponse response = client().get("/short");
        final StreamMessage<String> decoded = response.decode(new SimpleServerSentMessageDecoder());
        StepVerifier.create(Flux.from(decoded).log())
                    .expectNext("id:0\ndata:0\nretry:5000")
                    .expectNext("id:1\ndata:1\nretry:5000")
                    .expectNext("id:2\ndata:2\nretry:5000")
                    .expectNext("id:3\ndata:3\nretry:5000")
                    .expectNext("id:4\ndata:4\nretry:5000")
                    .expectComplete()
                    .verify();
    }

    private static class SimpleServerSentMessageDecoder implements HttpDecoder<String> {

        @Nullable
        private String buffer;

        @Override
        public void processHeaders(HttpHeaders headers, HttpDecoderOutput<String> out) throws Exception {
            assertThat(headers)
                    .isEqualTo(ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_TYPE,
                                                  MediaType.EVENT_STREAM));
        }

        @Override
        public void process(HttpDecoderInput in, HttpDecoderOutput<String> out) throws Exception {
            final int readableBytes = in.readableBytes();
            final ByteBuf byteBuf = in.readBytes(readableBytes);
            final String data;
            if (buffer != null) {
                data = buffer + byteBuf.toString(StandardCharsets.UTF_8);
                buffer = null;
            } else {
                data = byteBuf.toString(StandardCharsets.UTF_8);
            }

            int begin = 0;
            for (;;) {
                final int delim = data.indexOf("\n\n", begin);
                if (delim < 0) {
                    // not enough data
                    buffer = data.substring(begin);
                    return;
                } else {
                    out.add(data.substring(begin, delim));
                    begin = delim + 2;
                }
            }
        }
    }

    private static WebClient client() {
        return WebClient.of(server.httpUri());
    }
}
