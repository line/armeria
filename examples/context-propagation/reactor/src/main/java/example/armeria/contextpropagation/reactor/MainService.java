package example.armeria.contextpropagation.reactor;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public class MainService implements HttpService {

    private final WebClient backendClient;

    public MainService(WebClient backendClient) {
        this.backendClient = requireNonNull(backendClient, "backendClient");
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
        final Scheduler contextAwareScheduler = Schedulers.fromExecutor(ctx.eventLoop());

        // This logic mimics using a blocking method, which would usually be something like a MySQL
        // database query using JDBC.
        final Flux<Long> fetchNumsFromFakeDb =
                Mono.fromCallable(
                        () -> {
                            // The context is mounted in a thread-local, meaning it is available to all
                            // logic such as tracing.
                            assert ServiceRequestContext.current() == ctx;
                            assert !ctx.eventLoop().inEventLoop();

                            try {
                                // Simulate a blocking API call.
                                Thread.sleep(50);
                            } catch (Exception ignored) {
                                // Do nothing.
                            }
                            return Arrays.asList(23L, -23L);
                        })
                    // Always run blocking logic on the blocking task executor. By using
                    // ServiceRequestContext.blockingTaskExecutor, you also ensure the context is mounted
                    // inside the logic (e.g., your DB call will be traced!).
                    .subscribeOn(Schedulers.fromExecutor(ctx.blockingTaskExecutor()))
                    .flatMapIterable(Function.identity());

        final Flux<Long> extractNumsFromRequest =
                Mono.fromCompletionStage(req.aggregate())
                    // Unless you know what you're doing, always use subscribeOn with the context-aware
                    // scheduler to have the context mounted and stay on a single thread to reduce
                    // concurrency issues.
                    .subscribeOn(contextAwareScheduler)
                    .flatMapMany(request -> {
                        // The context is mounted in a thread-local, meaning it is available to all
                        // logic such as tracing.
                        assert ServiceRequestContext.current() == ctx;
                        assert ctx.eventLoop().inEventLoop();

                        final List<Long> nums = new ArrayList<>();
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

                        return Flux.fromIterable(nums);
                    });

        final Mono<HttpResponse> response =
                Flux.concat(extractNumsFromRequest, fetchNumsFromFakeDb)
                    // Unless you know what you're doing, always use subscribeOn with the context-aware
                    // scheduler to have the context mounted and stay on a single thread
                    // to reduce concurrency issues.
                    .subscribeOn(contextAwareScheduler)
                    // When concatenating fluxes, you should almost always call publishOn with the
                    // context-aware scheduler because we don't know here whether the subscription is on it or
                    // something like a blocking task executor.
                    .publishOn(contextAwareScheduler)
                    .flatMap(num -> {
                        // The context is mounted in a thread-local, meaning it is available to all logic
                        // such as tracing.
                        assert ServiceRequestContext.current() == ctx;
                        assert ctx.eventLoop().inEventLoop();

                        return Mono.fromCompletionStage(backendClient.get("/square/" + num).aggregate());
                    })
                    .map(AggregatedHttpResponse::contentUtf8)
                    .collect(StringBuilder::new, (current, item) -> current.append(item).append('\n'))
                    .map(content -> HttpResponse.of(content.toString()))
                    .onErrorResume(t -> Mono.just(HttpResponse.ofFailure(t)));

        return HttpResponse.from(response.toFuture());
    }
}
