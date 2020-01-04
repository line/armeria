package example.armeria.grpc.reactor;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.logging.LoggerFactory;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;

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
        final HelloReply reply = helloService.hello(HelloRequest.newBuilder()
                                                                .setName("Armeria")
                                                                .build()).block();
        assertThat(reply).isNotNull();
        assertThat(reply.getMessage()).isEqualTo("Hello, Armeria!");
    }

    @Test
    void getReplyWithDelay() {
        final HelloReply reply = helloService.lazyHello(HelloRequest.newBuilder()
                                                                    .setName("Armeria")
                                                                    .build()).block();
        assertThat(reply).isNotNull();
        assertThat(reply.getMessage()).isEqualTo("Hello, Armeria!");
    }

    @Test
    void getReplyFromServerSideBlockingCall() {
        final Stopwatch watch = Stopwatch.createStarted();
        final HelloReply reply = helloService.blockingHello(HelloRequest.newBuilder()
                                                                        .setName("Armeria")
                                                                        .build()).block();
        assertThat(reply).isNotNull();
        assertThat(reply.getMessage()).isEqualTo("Hello, Armeria!");
        assertThat(watch.elapsed(TimeUnit.SECONDS)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void getLotsOfReplies() {
        final List<HelloReply> replies =
                helloService.lotsOfReplies(HelloRequest.newBuilder().setName("Armeria").build())
                            .toStream().collect(toImmutableList());

        assertThat(replies).hasSize(5);

        for (int i = 0; i < replies.size(); i++) {
            assertThat(replies.get(i).getMessage()).isEqualTo("Hello, Armeria! (sequence: " + (i + 1) + ')');
        }
    }

    @Test
    void sendLotsOfGreetings() {
        final String[] names = { "Armeria", "Grpc", "Streaming" };
        final Flux<HelloRequest> request = Flux.just(names).log()
                                               .map(name -> HelloRequest.newBuilder().setName(name).build());

        final HelloReply reply = helloService.lotsOfGreetings(request).block();

        assertThat(reply).isNotNull();
        assertThat(reply.getMessage()).isEqualTo(HelloServiceImpl.toMessage(String.join(", ", names)));
    }

    @Test
    void bidirectionalHello() {
        final String[] names = { "Armeria", "Grpc", "Streaming" };
        final Flux<HelloRequest> request = Flux.just(names)
                                               .map(name -> HelloRequest.newBuilder().setName(name).build());
        final ImmutableList<HelloReply> replies =
                helloService.bidiHello(request).toStream().collect(toImmutableList());

        assertThat(replies).hasSize(names.length);

        for (int i = 0; i < names.length; i++) {
            assertThat(replies.get(i).getMessage()).isEqualTo(HelloServiceImpl.toMessage(names[i]));
        }
    }
}
