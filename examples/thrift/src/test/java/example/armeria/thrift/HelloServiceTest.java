package example.armeria.thrift;

import static example.armeria.thrift.Main.configureServices;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.apache.thrift.TException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.thrift.ThriftFuture;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HelloServiceTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            configureServices(sb);
        }
    };

    @Test
    void getReply() throws TException {
        final ThriftFuture<HelloReply> future = new ThriftFuture<>();
        helloService().hello(new HelloRequest("Armeria"), future);
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
        helloService().lazyHello(new HelloRequest("Armeria"), future);
        assertThat(future.join().getMessage()).isEqualTo("Hello, Armeria!");
    }

    @Test
    void getReplyFromServerSideBlockingCall() throws TException {
        final ThriftFuture<HelloReply> future = new ThriftFuture<>();
        final long startTime = System.nanoTime();
        helloService().blockingHello(new HelloRequest("Armeria"), future);

        assertThat(future.join().getMessage()).isEqualTo("Hello, Armeria!");
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime)).isGreaterThanOrEqualTo(3);
    }

    private static HelloService.AsyncIface helloService() {
        return Clients.newClient(uri(), HelloService.AsyncIface.class);
    }

    private static String uri() {
        return server.httpUri(ThriftSerializationFormats.BINARY).toString();
    }
}
