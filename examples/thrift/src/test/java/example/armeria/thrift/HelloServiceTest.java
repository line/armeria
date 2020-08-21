package example.armeria.thrift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.apache.thrift.TException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.thrift.ThriftFuture;
import com.linecorp.armeria.server.Server;

class HelloServiceTest {

    private static Server server;
    private static HelloService.AsyncIface helloService;

    @BeforeAll
    static void beforeClass() throws Exception {
        server = Main.newServer(0, 0);
        server.start().join();
        helloService = Clients.newClient(uri(), HelloService.AsyncIface.class);
    }

    @AfterAll
    static void afterClass() {
        if (server != null) {
            server.stop().join();
        }
    }

    private static String uri() {
        return "tbinary+http://127.0.0.1:" + server.activeLocalPort() + '/';
    }

    @Test
    void getReply() throws TException {
        final ThriftFuture<HelloReply> future = new ThriftFuture<>();
        helloService.hello(new HelloRequest("Armeria"), future);
        assertThat(future.join().getMessage()).isEqualTo("Hello, Armeria!");
    }

    @Test
    void getReplyWithBlockingCall() throws TException {
        final HelloService.Iface helloService = Clients.newClient(uri(), HelloService.Iface.class);
        final HelloReply reply = helloService.hello(new HelloRequest("Armeria"));
        assertThat(reply.getMessage()).isEqualTo("Hello, Armeria!");
    }

    @Test
    void getReplyWithDelay() throws TException {
        final ThriftFuture<HelloReply> future = new ThriftFuture<>();
        helloService.lazyHello(new HelloRequest("Armeria"), future);
        assertThat(future.join().getMessage()).isEqualTo("Hello, Armeria!");
    }

    @Test
    void getReplyFromServerSideBlockingCall() throws TException {
        final ThriftFuture<HelloReply> future = new ThriftFuture<>();
        final long startTime = System.nanoTime();
        helloService.blockingHello(new HelloRequest("Armeria"), future);

        assertThat(future.join().getMessage()).isEqualTo("Hello, Armeria!");
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime)).isGreaterThanOrEqualTo(3);
    }
}
