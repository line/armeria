package example.armeria.server.sse;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.function.Supplier;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.sse.ServerSentEvent;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.ProducesEventStream;
import com.linecorp.armeria.server.file.HttpFile;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.streaming.ServerSentEvents;

import reactor.core.publisher.Flux;

public final class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    private static final SecureRandom random = new SecureRandom();

    public static void main(String[] args) throws Exception {
        final Server server = newServer(8080, 8443,
                                        Duration.ofSeconds(1), Long.MAX_VALUE,
                                        () -> Long.toHexString(random.nextLong()));

        server.closeOnShutdown();

        server.start().join();
        logger.info("Server has been started.");
    }

    private static Server newServer(int httpPort, int httpsPort, Duration sendingInterval, long eventCount,
                                    Supplier<String> randomStringSupplier) throws Exception {
        final ServerBuilder sb = Server.builder();
        sb.http(httpPort)
          .https(httpsPort)
          .tlsSelfSigned();
        configureServices(sb, sendingInterval, eventCount, randomStringSupplier);
        return sb.build();
    }

    static void configureServices(ServerBuilder sb, Duration sendingInterval, long eventCount,
                                  Supplier<String> randomStringSupplier) throws Exception {
        sb.service("/long", (ctx, req) -> {
              // Note that you MUST adjust the request timeout if you want to send events for a
              // longer period than the configured request timeout. The timeout can be disabled by
              // 'clearRequestTimeout()' like the below, but it is NOT RECOMMENDED in
              // the real world application, because it can leave a lot of unfinished requests.
              ctx.clearRequestTimeout();
              return ServerSentEvents.fromPublisher(
                      Flux.interval(sendingInterval)
                          .onBackpressureDrop()
                          .take(eventCount)
                          .map(unused -> ServerSentEvent.ofData(randomStringSupplier.get())));
          }).annotatedService(new Object() {
              // This shows how you can send events in the annotated HTTP service.
              @Get("/short")
              @ProducesEventStream
              public Publisher<ServerSentEvent> sendEvents() {
                  // The event stream will be closed after
                  // the request timed out (10 seconds by default).
                  return Flux.interval(sendingInterval)
                             .onBackpressureDrop()
                             .take(eventCount)
                             // A user can use a builder to build a Server-Sent Event.
                             .map(id -> ServerSentEvent.builder()
                                                       .id(Long.toString(id))
                                                       .data(randomStringSupplier.get())
                                                       // The client will reconnect to this server
                                                       // after 5 seconds when the on-going stream
                                                       // is closed.
                                                       .retry(Duration.ofSeconds(5))
                                                       .build());
              }
          })
          .service("/", HttpFile.of(Main.class.getClassLoader(), "index.html").asService())
          .decorator(LoggingService.newDecorator())
          .disableServerHeader()
          .disableDateHeader();
    }

    private Main() {}
}
