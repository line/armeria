package example.armeria.contextpropagation.manual;

import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

public class MainService implements HttpService {

    private final WebClient backendClient;

    public MainService(WebClient backendClient) {
        this.backendClient = requireNonNull(backendClient, "backendClient");
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
        final Executor ctxExecutor = ctx.eventLoop();

        final CompletableFuture<AggregatedHttpRequest> aggregated = req.aggregate();

        // This logic mimics using a blocking method, which would usually be something like a MySQL
        // database query using JDBC.
        final CompletableFuture<List<Long>> fetchFromFakeDb = CompletableFuture.supplyAsync(
                () -> {
                    // The context is mounted in a thread-local, meaning it is available to all logic such
                    // as tracing.
                    assert ServiceRequestContext.current() == ctx;
                    assert !ctx.eventLoop().inEventLoop();

                    try {
                        // Simulate a blocking API call.
                        Thread.sleep(50);
                    } catch (Exception ignored) {
                        // Do nothing.
                    }
                    return Arrays.asList(23L, -23L);
                },
                // Always run blocking logic on the blocking task executor. By using
                // ServiceRequestContext.blockingTaskExecutor, you also ensure the context is mounted inside the
                // logic (e.g., your DB call will be traced!).
                ctx.blockingTaskExecutor());

        final CompletableFuture<List<CompletableFuture<AggregatedHttpResponse>>> fetchFromBackend =
                CompletableFuture.allOf(
                        aggregated, fetchFromFakeDb).thenApplyAsync(
                        unused -> {
                            // The context is mounted in a thread-local, meaning it is available to all logic
                            // such as tracing.
                            assert ServiceRequestContext.current() == ctx;
                            assert ctx.eventLoop().inEventLoop();

                            final AggregatedHttpRequest request = aggregated.join();

                            final Stream.Builder<Long> nums = Stream.builder();
                            Arrays.stream(request.path().substring(1).split(",")).forEach(token -> {
                                if (!token.isEmpty()) {
                                    nums.add(Long.parseLong(token));
                                }
                            });
                            Arrays.stream(request.contentUtf8().split(",")).forEach(token -> {
                                if (!token.isEmpty()) {
                                    nums.add(Long.parseLong(token));
                                }
                            });
                            fetchFromFakeDb.join().forEach(nums::add);

                            return nums.build()
                                       .map(num -> backendClient.get("/square/" + num).aggregate())
                                       .collect(Collectors.toList());
                        },
                        // Unless you know what you're doing, always use then*Async type methods with the
                        // context-aware executor to have the context mounted and stay on a single
                        // thread to reduce concurrency issues.
                        ctxExecutor);

        final CompletableFuture<HttpResponse> response =
                // When using CompletableFuture, boiler-plate invocations to wrap / unwrap futures are sometimes
                // required. Such boilerplate has no chance of using Armeria's context and it is ok to not
                // use ctxExecutor for them. But if in doubt, it doesn't hurt too much to use it everywhere.
                fetchFromBackend.thenApply(CompletableFutures::allAsList)
                                .thenCompose(u -> u)
                                .thenApplyAsync(
                                        backendResponse -> {
                                            // The context is mounted in a thread-local, meaning it is
                                            // available to all logic such as tracing.
                                            assert ServiceRequestContext.current() == ctx;
                                            assert ctx.eventLoop().inEventLoop();
                                            return HttpResponse.of(
                                                    backendResponse.stream()
                                                                   .map(AggregatedHttpResponse::contentUtf8)
                                                                   .collect(Collectors.joining("\n")));
                                        },
                                        // Unless you know what you're doing, always use then*Async type
                                        // methods with the context-aware executor to have the context
                                        // mounted and stay on a single thread to reduce concurrency issues.
                                        ctxExecutor);

        return HttpResponse.of(response);
    }
}
