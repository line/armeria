package example.armeria.grpc.reactor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.server.Server;

import example.armeria.grpc.reactor.Hello.HelloReply;
import example.armeria.grpc.reactor.Hello.HelloRequest;
import io.grpc.stub.StreamObserver;

class HelloServiceTest {

    private static Server server;
    private static HelloServiceGrpc.HelloServiceStub helloService;

    @BeforeAll
    static void beforeClass() throws Exception {
        server = Main.newServer(0, 0);
        server.start().join();
        helloService = Clients.newClient(uri(), HelloServiceGrpc.HelloServiceStub.class);
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
        final HelloServiceGrpc.HelloServiceBlockingStub helloService =
                Clients.newClient(uri(), HelloServiceGrpc.HelloServiceBlockingStub.class);
        assertThat(helloService.hello(HelloRequest.newBuilder().setName("Armeria").build()).getMessage())
                .isEqualTo("Hello, Armeria!");
    }

    @Test
    void getReplyWithDelay() {
        final HelloServiceGrpc.HelloServiceFutureStub helloService =
                Clients.newClient(uri(), HelloServiceGrpc.HelloServiceFutureStub.class);
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
        final HelloServiceGrpc.HelloServiceBlockingStub helloService =
                Clients.newClient(uri(), HelloServiceGrpc.HelloServiceBlockingStub.class);
        final Stopwatch watch = Stopwatch.createStarted();
        assertThat(helloService.blockingHello(HelloRequest.newBuilder().setName("Armeria").build())
                               .getMessage()).isEqualTo("Hello, Armeria!");
        assertThat(watch.elapsed(TimeUnit.SECONDS)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void getLotsOfReplies() {
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
                                .isEqualTo(HelloServiceImpl.toMessage(String.join(", ", names)));
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
        final String[] names = { "Armeria", "Grpc", "Streaming" };
        final AtomicBoolean completed = new AtomicBoolean();
        final StreamObserver<HelloRequest> request =
                helloService.bidiHello(new StreamObserver<HelloReply>() {
                    private int received;

                    @Override
                    public void onNext(HelloReply value) {
                        assertThat(value.getMessage())
                                .isEqualTo(HelloServiceImpl.toMessage(names[received++]));
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
}
