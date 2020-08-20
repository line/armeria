package example.armeria.contextpropagation.dagger;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.spotify.futures.CompletableFuturesExtra.toListenableFuture;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
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
 * be run in parallel (here, fetchFromFakeDb and aggregateRequest are executed in parallel).
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

    private static final Splitter NUM_SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();

    @Produces
    static ListenableFuture<AggregatedHttpRequest> aggregateRequest(
            HttpRequest request, ServiceRequestContext context) {
        // The context is mounted in a thread-local, meaning it is available to all logic such as tracing.
        checkState(ServiceRequestContext.current() == context);
        checkState(context.eventLoop().inEventLoop());
        return toListenableFuture(request.aggregate());
    }

    @Produces
    static ListenableFuture<List<Long>> fetchFromFakeDb(ServiceRequestContext context,
                                                        ListeningScheduledExecutorService blockingExecutor) {
        // The context is mounted in a thread-local, meaning it is available to all logic such as tracing.
        checkState(ServiceRequestContext.current() == context);
        checkState(context.eventLoop().inEventLoop());
        // This logic mimics using a blocking method, which would usually be something like a MySQL database
        // query using JDBC.
        // Always run blocking logic on the blocking task executor. By using
        // ServiceRequestContext.blockingTaskExecutor (indirectly via the ListeningScheduledExecutorService
        // wrapper we defined in MainModule), you also ensure the context is mounted inside the logic (e.g.,
        // your DB call will be traced!).
        return blockingExecutor.submit(() -> {
            // The context is mounted in a thread-local, meaning it is available to all logic such as tracing.
            checkState(ServiceRequestContext.current() == context);
            checkState(!context.eventLoop().inEventLoop());

            Uninterruptibles.sleepUninterruptibly(Duration.ofMillis(50));
            return ImmutableList.of(23L, -23L);
        });
    }

    @Produces
    static ListenableFuture<List<AggregatedHttpResponse>> fetchFromBackend(
            AggregatedHttpRequest request, List<Long> dbNums, WebClient backendClient,
            ServiceRequestContext context) {
        // The context is mounted in a thread-local, meaning it is available to all logic such as tracing.
        checkState(ServiceRequestContext.current() == context);
        checkState(context.eventLoop().inEventLoop());

        final Stream.Builder<Long> nums = Stream.builder();
        for (String token : Iterables.concat(
                NUM_SPLITTER.split(request.path().substring(1)),
                NUM_SPLITTER.split(request.contentUtf8()))) {
            nums.add(Long.parseLong(token));
        }
        dbNums.forEach(nums::add);

        return Futures.allAsList(
                nums.build()
                    .map(num -> toListenableFuture(backendClient.get("/square/" + num).aggregate()))
                    .collect(toImmutableList()));
    }

    @Produces
    static HttpResponse buildResponse(List<AggregatedHttpResponse> backendResponse,
                                      ServiceRequestContext context) {
        // The context is mounted in a thread-local, meaning it is available to all logic such as tracing.
        checkState(ServiceRequestContext.current() == context);
        checkState(context.eventLoop().inEventLoop());
        return HttpResponse.of(backendResponse.stream()
                                              .map(AggregatedHttpResponse::contentUtf8)
                                              .collect(Collectors.joining("\n")));
    }
}
