package example.armeria.thrift;

import java.util.concurrent.TimeUnit;

import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;

import com.linecorp.armeria.server.ServiceRequestContext;

class HelloServiceImpl implements HelloService.AsyncIface {

    /**
     * Sends a {@link HelloReply} immediately when receiving a request.
     */
    @Override
    public void hello(HelloRequest request, AsyncMethodCallback<HelloReply> resultHandler) throws TException {
        // Make sure that current thread is request context aware
        ServiceRequestContext.current();
        resultHandler.onComplete(buildReply(toMessage(request.getName())));
    }

    /**
     * Sends a {@link HelloReply} 3 seconds after receiving a request.
     */
    @Override
    public void lazyHello(HelloRequest request, AsyncMethodCallback<HelloReply> resultHandler)
            throws TException {

        // You can use the event loop for scheduling a task.
        ServiceRequestContext.current().eventLoop().schedule(() -> {
            resultHandler.onComplete(buildReply(toMessage(request.getName())));
        }, 3, TimeUnit.SECONDS);
    }

    /**
     * Sends a {@link HelloReply} using {@code blockingTaskExecutor}.
     */
    @Override
    public void blockingHello(HelloRequest request, AsyncMethodCallback<HelloReply> resultHandler)
            throws TException {

        ServiceRequestContext.current().blockingTaskExecutor().execute(() -> {
            try {
                // Simulate a blocking API call.
                Thread.sleep(3000);
            } catch (Exception ignored) {
                // Do nothing.
            }
            resultHandler.onComplete(buildReply(toMessage(request.getName())));
        });
    }

    static String toMessage(String name) {
        return "Hello, " + name + '!';
    }

    private static HelloReply buildReply(String message) {
        return new HelloReply(message);
    }
}
