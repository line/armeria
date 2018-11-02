package example.armeria.proxy;

import java.util.Arrays;
import java.util.List;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

public final class AnimationService extends AbstractHttpService {

    private static final List<String> frames = Arrays.asList(
            "<pre>" +
            "╔════╤╤╤╤════╗\n" +
            "║    │││ \\   ║\n" +
            "║    │││  O  ║\n" +
            "║    OOO     ║" +
            "</pre>",

            "<pre>" +
            "╔════╤╤╤╤════╗\n" +
            "║    ││││    ║\n" +
            "║    ││││    ║\n" +
            "║    OOOO    ║" +
            "</pre>",

            "<pre>" +
            "╔════╤╤╤╤════╗\n" +
            "║   / │││    ║\n" +
            "║  O  │││    ║\n" +
            "║     OOO    ║" +
            "</pre>",

            "<pre>" +
            "╔════╤╤╤╤════╗\n" +
            "║    ││││    ║\n" +
            "║    ││││    ║\n" +
            "║    OOOO    ║" +
            "</pre>"
    );

    private final int pendulumDuration;

    public AnimationService(int pendulumDuration) {
        this.pendulumDuration = pendulumDuration;
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        // Create a response for streaming. If you don't need to stream, use HttpResponse.of(...) instead.
        final HttpResponseWriter res = HttpResponse.streaming();
        res.write(HttpHeaders.of(HttpStatus.OK)
                             .contentType(MediaType.PLAIN_TEXT_UTF_8));

        ctx.blockingTaskExecutor().submit(() -> {
            for (int i = 0; i < 1000; i++) {
                try {
                    Thread.sleep(pendulumDuration);
                } catch (Exception e) {
                    res.close(e);
                    return;
                }
                res.write(HttpData.ofUtf8(frames.get(i % 4)));
            }
            res.close();
        });
        return res;
    }
}
