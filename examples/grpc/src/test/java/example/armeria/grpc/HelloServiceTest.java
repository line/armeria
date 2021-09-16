package example.armeria.grpc;

import static example.armeria.grpc.HelloServiceImpl.toMessage;
import static example.armeria.grpc.Main.configureServices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import example.armeria.grpc.Hello.HelloReply;
import example.armeria.grpc.Hello.HelloRequest;
import example.armeria.grpc.HelloServiceGrpc.HelloServiceBlockingStub;
import example.armeria.grpc.HelloServiceGrpc.HelloServiceFutureStub;
import example.armeria.grpc.HelloServiceGrpc.HelloServiceStub;
import io.grpc.stub.StreamObserver;

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
        final HelloServiceBlockingStub helloService = Clients.newClient(uri(), HelloServiceBlockingStub.class);
        assertThat(helloService.hello(HelloRequest.newBuilder().setName("Armeria").build()).getMessage())
                .isEqualTo("Hello, Armeria!");
    }

    @Test
    void testsTheErrorUnframedResponseWithJsonContentType() throws InvalidProtocolBufferException {
        var client = WebClient.builder(server.httpUri())
                              .addHeader("content-type", "application/json; charset=utf-8; protocol=gRPC")
                              .build();
        var response = client.post("example.grpc.hello.HelloService/Hello",
                                   JsonFormat.printer().print(HelloRequest.getDefaultInstance()))
                             .aggregate()
                             .join()
                             .contentUtf8();
        assertThat(response).isEqualTo(
                "{\"grpc-code\":\"FAILED_PRECONDITION\",\"message\":\"Name cannot be empty\"}");
    }

    @Test
    void getReplyWithDelay() {
        final HelloServiceFutureStub helloService = Clients.newClient(uri(), HelloServiceFutureStub.class);
        final ListenableFuture<HelloReply> future =
                helloService.lazyHello(HelloRequest.newBuilder().setName("Armeria").build());
        final AtomicBoolean completed = new AtomicBoolean();
        Futures.addCallback(future, new FutureCallback<HelloReply>() {
            @Override
            public void onSuccess(HelloReply result) {
                assertThat(result.getMessage()).isEqualTo("Hello, Armeria!");
                completed.set(true);
            }

            @Override
            public void onFailure(Throwable t) {
                // Should never reach here.
                throw new Error(t);
            }
        }, MoreExecutors.directExecutor());

        await().untilTrue(completed);
    }

    @Test
    void getReplyFromServerSideBlockingCall() {
        final HelloServiceBlockingStub helloService = Clients.newClient(uri(), HelloServiceBlockingStub.class);
        final Stopwatch watch = Stopwatch.createStarted();
        assertThat(helloService.blockingHello(HelloRequest.newBuilder().setName("Armeria").build())
                               .getMessage()).isEqualTo("Hello, Armeria!");
        assertThat(watch.elapsed(TimeUnit.SECONDS)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void getLotsOfReplies() {
        final HelloServiceStub helloService = helloService();
        final AtomicBoolean completed = new AtomicBoolean();
        helloService.lotsOfReplies(
                HelloRequest.newBuilder().setName("Armeria").build(),
                new StreamObserver<HelloReply>() {
                    private int sequence;

                    @Override
                    public void onNext(HelloReply value) {
                        assertThat(value.getMessage())
                                .isEqualTo("Hello, Armeria! (sequence: " + ++sequence + ')');
                    }

                    @Override
                    public void onError(Throwable t) {
                        // Should never reach here.
                        throw new Error(t);
                    }

                    @Override
                    public void onCompleted() {
                        assertThat(sequence).isEqualTo(5);
                        completed.set(true);
                    }
                });
        await().untilTrue(completed);
    }

    @Test
    void blockForLotsOfReplies() throws Exception {
        final HelloServiceStub helloService = helloService();
        final BlockingQueue<HelloReply> replies = new LinkedBlockingQueue<>();
        final AtomicBoolean completed = new AtomicBoolean();
        helloService.lotsOfReplies(
                HelloRequest.newBuilder().setName("Armeria").build(),
                new StreamObserver<HelloReply>() {

                    @Override
                    public void onNext(HelloReply value) {
                        replies.offer(value);
                    }

                    @Override
                    public void onError(Throwable t) {
                        // Should never reach here.
                        throw new Error(t);
                    }

                    @Override
                    public void onCompleted() {
                        completed.set(true);
                    }
                });
        int sequence = 0;
        while (!completed.get() || !replies.isEmpty()) {
            final HelloReply value = replies.poll(100, TimeUnit.MILLISECONDS);
            if (value == null) {
                // Timed out, try again.
                continue;
            }
            assertThat(value.getMessage())
                    .isEqualTo("Hello, Armeria! (sequence: " + ++sequence + ')');
        }
        assertThat(sequence).isEqualTo(5);
    }

    @Test
    void sendLotsOfGreetings() {
        final HelloServiceStub helloService = helloService();
        final String[] names = { "Armeria", "Grpc", "Streaming" };
        final AtomicBoolean completed = new AtomicBoolean();
        final StreamObserver<HelloRequest> request =
                helloService.lotsOfGreetings(new StreamObserver<HelloReply>() {
                    private boolean received;

                    @Override
                    public void onNext(HelloReply value) {
                        assertThat(received).isFalse();
                        received = true;
                        assertThat(value.getMessage())
                                .isEqualTo(toMessage(String.join(", ", names)));
                    }

                    @Override
                    public void onError(Throwable t) {
                        // Should never reach here.
                        throw new Error(t);
                    }

                    @Override
                    public void onCompleted() {
                        assertThat(received).isTrue();
                        completed.set(true);
                    }
                });

        for (String name : names) {
            request.onNext(HelloRequest.newBuilder().setName(name).build());
        }
        request.onCompleted();
        await().untilTrue(completed);
    }

    @Test
    void bidirectionalHello() {
        final HelloServiceStub helloService = helloService();
        final String[] names = { "Armeria", "Grpc", "Streaming" };
        final AtomicBoolean completed = new AtomicBoolean();
        final StreamObserver<HelloRequest> request =
                helloService.bidiHello(new StreamObserver<HelloReply>() {
                    private int received;

                    @Override
                    public void onNext(HelloReply value) {
                        assertThat(value.getMessage())
                                .isEqualTo(toMessage(names[received++]));
                    }

                    @Override
                    public void onError(Throwable t) {
                        // Should never reach here.
                        throw new Error(t);
                    }

                    @Override
                    public void onCompleted() {
                        assertThat(received).isEqualTo(names.length);
                        completed.set(true);
                    }
                });

        for (String name : names) {
            request.onNext(HelloRequest.newBuilder().setName(name).build());
        }
        request.onCompleted();
        await().untilTrue(completed);
    }

    private static HelloServiceStub helloService() {
        return Clients.newClient(uri(), HelloServiceStub.class);
    }

    private static String uri() {
        return server.httpUri(GrpcSerializationFormats.PROTO).toString();
    }
}
