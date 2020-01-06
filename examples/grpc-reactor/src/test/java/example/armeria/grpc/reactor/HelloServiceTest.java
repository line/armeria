package example.armeria.grpc.reactor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.common.base.Stopwatch;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.server.Server;

import example.armeria.grpc.reactor.Hello.HelloReply;
import example.armeria.grpc.reactor.Hello.HelloRequest;
import reactor.core.publisher.Flux;

class HelloServiceTest {

    private static Server server;
    private static ReactorHelloServiceGrpc.ReactorHelloServiceStub helloService;

    @BeforeAll
    static void beforeClass() throws Exception {
        server = Main.newServer(0, 0);
        server.start().join();
        helloService = Clients.newClient(uri(), ReactorHelloServiceGrpc.ReactorHelloServiceStub.class);
    }

    @AfterAll
    static void afterClass() {
        if (server != null) {
            server.stop().join();
        }
    }

    private static String uri() {
        return "gproto+http://127.0.0.1:" + server.activeLocalPort() + '/';
    }

    @Test
    void getReply() {
        final String message = helloService.hello(HelloRequest.newBuilder()
                                                              .setName("Armeria")
                                                              .build())
                                           .map(HelloReply::getMessage)
                                           .block();
        assertThat(message).isEqualTo("Hello, Armeria!");
    }

    @Test
    void getReplyWithDelay() {
        final String message = helloService.lazyHello(HelloRequest.newBuilder()
                                                                  .setName("Armeria")
                                                                  .build())
                                           .map(HelloReply::getMessage)
                                           .block();
        assertThat(message).isEqualTo("Hello, Armeria!");
    }

    @Test
    void getReplyFromServerSideBlockingCall() {
        final Stopwatch watch = Stopwatch.createStarted();
        final String message = helloService.blockingHello(HelloRequest.newBuilder()
                                                                      .setName("Armeria")
                                                                      .build())
                                           .map(HelloReply::getMessage)
                                           .block();
        assertThat(message).isEqualTo("Hello, Armeria!");
        assertThat(watch.elapsed(TimeUnit.SECONDS)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void getLotsOfReplies() {
        final List<String> messages =
                helloService.lotsOfReplies(HelloRequest.newBuilder().setName("Armeria").build())
                            .map(HelloReply::getMessage)
                            .collectList()
                            .block();

        assertThat(messages).hasSize(5);

        for (int i = 0; i < messages.size(); i++) {
            assertThat(messages.get(i)).isEqualTo("Hello, Armeria! (sequence: " + (i + 1) + ')');
        }
    }

    @Test
    void sendLotsOfGreetings() {
        final String message = Flux.just("Armeria", "Grpc", "Streaming").log()
                                   .map(name -> HelloRequest.newBuilder().setName(name).build())
                                   .as(helloService::lotsOfGreetings)
                                   .map(HelloReply::getMessage)
                                   .block();
        assertThat(message).isEqualTo("Hello, Armeria, Grpc, Streaming!");
    }

    @Test
    void bidirectionalHello() {
        final String[] names = { "Armeria", "Grpc", "Streaming" };
        final List<String> messages = Flux.just(names)
                                          .map(name -> HelloRequest.newBuilder().setName(name).build())
                                          .as(helloService::bidiHello)
                                          .map(HelloReply::getMessage)
                                          .collectList()
                                          .block();
        assertThat(messages).hasSize(names.length);

        for (int i = 0; i < names.length; i++) {
            assertThat(messages.get(i)).isEqualTo("Hello, " + names[i] + '!');
        }
    }
}
