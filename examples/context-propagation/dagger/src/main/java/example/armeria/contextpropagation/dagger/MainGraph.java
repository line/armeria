package example.armeria.contextpropagation.dagger;

import static com.google.common.base.Preconditions.checkState;
import static com.spotify.futures.CompletableFuturesExtra.toListenableFuture;

import java.time.Duration;
import java.util.concurrent.Executor;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.Uninterruptibles;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServiceRequestContext;

import dagger.BindsInstance;
import dagger.producers.ProducerModule;
import dagger.producers.Produces;
import dagger.producers.Production;
import dagger.producers.ProductionSubcomponent;
import example.armeria.contextpropagation.dagger.Main.MainModule;

/**
 * The Dagger {@link ProducerModule} that models the asynchronous steps of executing a request. Dagger's
 * annotation compiler automatically generates code to connect the return value of methods with parameters of
 * other ones, even when the return value is a {@link ListenableFuture} that completes asynchronously. Because
 * we defined a {@literal @}{@link Production} {@link Executor} in {@link MainModule}, all methods will be
 * executed with the {@link ServiceRequestContext} of the request mounted in the thread-local to support usage
 * like distributed tracing and logging. An extra bonus is that methods with no common input parameters will
 * be run in parallel (here, fetchFromFakeDb and fetchFromBackend are executed in parallel).
 */
@ProducerModule
public abstract class MainGraph {
    @ProductionSubcomponent(modules = MainGraph.class)
    public interface Component {
        ListenableFuture<HttpResponse> execute();

        @ProductionSubcomponent.Builder
        interface Builder {
            Builder request(@BindsInstance HttpRequest request);

            Component build();
        }
    }

    @Produces
    static ListenableFuture<AggregatedHttpRequest> aggregateRequest(
            HttpRequest request, ServiceRequestContext context) {
        // The context is mounted in a thread-local, meaning it is available to all logic such as tracing.
        checkState(ServiceRequestContext.current() == context);
        return toListenableFuture(request.aggregate());
    }

    @Produces
    static ListenableFuture<String> fetchFromFakeDb(AggregatedHttpRequest request,
                                                    ServiceRequestContext context,
                                                    ListeningScheduledExecutorService blockingExecutor) {
        // The context is mounted in a thread-local, meaning it is available to all logic such as tracing.
        checkState(ServiceRequestContext.current() == context);
        // This logic mimics using a blocking method, which would usually be something like a MySQL database
        // query using JDBC.
        return blockingExecutor.submit(() -> {
            // The context is mounted in a thread-local, meaning it is available to all logic such as tracing.
            checkState(ServiceRequestContext.current() == context);

            Uninterruptibles.sleepUninterruptibly(Duration.ofMillis(50));
            return request.path() + "-" + request.contentUtf8();
        });
    }

    @Produces
    static ListenableFuture<AggregatedHttpResponse> fetchFromBackend(WebClient backendClient,
                                                                     ServiceRequestContext context) {
        // The context is mounted in a thread-local, meaning it is available to all logic such as tracing.
        checkState(ServiceRequestContext.current() == context);
        return toListenableFuture(backendClient.get("/").aggregate());
    }

    @Produces
    static HttpResponse buildResponse(String dbResult, AggregatedHttpResponse backendResponse,
                                      ServiceRequestContext context) {
        // The context is mounted in a thread-local, meaning it is available to all logic such as tracing.
        checkState(ServiceRequestContext.current() == context);
        return HttpResponse.of(dbResult + ":" + backendResponse.contentUtf8());
    }
}
