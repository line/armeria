package example.armeria.grpc.reactor;

import static example.armeria.grpc.reactor.Main.configureServices;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Stopwatch;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import example.armeria.grpc.reactor.Hello.HelloReply;
import example.armeria.grpc.reactor.Hello.HelloRequest;
import reactor.core.publisher.Flux;

class HelloServiceTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            configureServices(sb);
        }
    };

    @Test
    void getReply() {
        final String message = helloService().hello(HelloRequest.newBuilder()
                                                                .setName("Armeria")
                                                                .build())
                                             .map(HelloReply::getMessage)
                                             .block();
        assertThat(message).isEqualTo("Hello, Armeria!");
    }

    @Test
    void getReplyWithDelay() {
        final String message = helloService().lazyHello(HelloRequest.newBuilder()
                                                                    .setName("Armeria")
                                                                    .build())
                                             .map(HelloReply::getMessage)
                                             .block();
        assertThat(message).isEqualTo("Hello, Armeria!");
    }

    @Test
    void getReplyFromServerSideBlockingCall() {
        final Stopwatch watch = Stopwatch.createStarted();
        final String message = helloService().blockingHello(HelloRequest.newBuilder()
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
                helloService().lotsOfReplies(HelloRequest.newBuilder().setName("Armeria").build())
                              .map(HelloReply::getMessage)
                              .collectList()
                              .block();

        assertThat(messages).hasSize(5);

        for (int i = 0; i < messages.size(); i++) {
            assertThat(messages.get(i)).isEqualTo("Hello, Armeria! (sequence: " + (i + 1) + ')');
        }
    }

    @Test
    void getLotsOfRepliesWithoutScheduler() {
        final List<String> messages =
                helloService().lotsOfRepliesWithoutScheduler(
                        HelloRequest.newBuilder().setName("Armeria").build())
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
                                   .as(helloService()::lotsOfGreetings)
                                   .map(HelloReply::getMessage)
                                   .block();
        assertThat(message).isEqualTo("Hello, Armeria, Grpc, Streaming!");
    }

    @Test
    void bidirectionalHello() {
        final String[] names = { "Armeria", "Grpc", "Streaming" };
        final List<String> messages = Flux.just(names)
                                          .map(name -> HelloRequest.newBuilder().setName(name).build())
                                          .as(helloService()::bidiHello)
                                          .map(HelloReply::getMessage)
                                          .collectList()
                                          .block();
        assertThat(messages).hasSize(names.length);

        for (int i = 0; i < names.length; i++) {
            assertThat(messages.get(i)).isEqualTo("Hello, " + names[i] + '!');
        }
    }

    private static ReactorHelloServiceGrpc.ReactorHelloServiceStub helloService() {
        return GrpcClients.newClient(server.httpUri(), ReactorHelloServiceGrpc.ReactorHelloServiceStub.class);
    }
}
