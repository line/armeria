package example.armeria.server.annotated;

import static com.google.common.base.Preconditions.checkArgument;

import java.time.Duration;
import java.util.List;

import org.reactivestreams.Publisher;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.sse.ServerSentEvent;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.ProducesEventStream;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;

import reactor.core.publisher.Flux;

/**
 * Examples how to send {@link ServerSentEvent}s.
 *
 * @see <a href="https://line.github.io/armeria/server-sse.html">Serving Server-Sent Events</a>
 */
@LoggingDecorator(
        requestLogLevel = LogLevel.INFO,            // Log every request sent to this service at INFO level.
        successfulResponseLogLevel = LogLevel.INFO  // Log every response sent from this service at INFO level.
)
public class ServerSentEventsService {

    private static final List<String> DATA = ImmutableList.of("foo", "bar", "baz", "qux");

    @Get("/{count}")
    @ProducesEventStream
    public Publisher<ServerSentEvent> sseWithCount(@Param int count) {
        checkArgument(count <= DATA.size(),
                      "count: %s (expected: <= %s)", count, DATA.size());
        // Emit events for every 200 millisecond.
        return Flux.interval(Duration.ofMillis(200))
                   .take(count)
                   .zipWithIterable(DATA)
                   .map(source -> ServerSentEvent.ofData(source.getT2()));
    }
}
