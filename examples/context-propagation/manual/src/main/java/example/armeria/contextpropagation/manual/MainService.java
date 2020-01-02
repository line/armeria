package example.armeria.contextpropagation.manual;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.google.common.util.concurrent.Uninterruptibles;

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
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final Executor ctxExecutor = ctx.contextAwareExecutor();

        final CompletableFuture<AggregatedHttpRequest> aggregated = req.aggregate();

        final CompletableFuture<String> fetchFromFakeDb = aggregated.thenComposeAsync(
                request -> {
                    // The context is mounted in a thread-local, meaning it is available to all logic such as
                    // tracing.
                    checkState(ServiceRequestContext.current() == ctx);

                    // This logic mimics using a blocking method, which would usually be something like a MySQL
                    // database query using JDBC.
                    return CompletableFuture.supplyAsync(() -> {
                        // The context is mounted in a thread-local, meaning it is available to all logic such
                        // as tracing.
                        checkState(ServiceRequestContext.current() == ctx);

                        Uninterruptibles.sleepUninterruptibly(Duration.ofMillis(50));
                        return request.path() + "-" + request.contentUtf8();
                    }, ctx.blockingTaskExecutor());
                },
                // Unless you know what you're doing, always use then*Async type methods with the context
                // executor to have the context mounted and stay on a single thread to reduce concurrency
                // issues.
                ctxExecutor);

        final CompletableFuture<AggregatedHttpResponse> fetchFromBackend = aggregated.thenComposeAsync(
                request -> {
                    // The context is mounted in a thread-local, meaning it is available to all logic such as
                    // tracing.
                    checkState(ServiceRequestContext.current() == ctx);

                    return backendClient.get("/").aggregate();
                },
                // Unless you know what you're doing, always use then*Async type methods with the context
                // executor to have the context mounted and stay on a single thread to reduce concurrency
                // issues.
                ctxExecutor);

        final CompletableFuture<HttpResponse> response = fetchFromBackend.thenCombineAsync(
                fetchFromFakeDb, (backendResponse, dbResult) -> {
                    // The context is mounted in a thread-local, meaning it is available to all logic such as
                    // tracing.
                    checkState(ServiceRequestContext.current() == ctx);
                    return HttpResponse.of(dbResult + ":" + backendResponse.contentUtf8());
                },
                // Unless you know what you're doing, always use then*Async type methods with the context
                // executor to have the context mounted and stay on a single thread to reduce concurrency
                // issues.
                ctxExecutor);

        return HttpResponse.from(response);
    }
}
