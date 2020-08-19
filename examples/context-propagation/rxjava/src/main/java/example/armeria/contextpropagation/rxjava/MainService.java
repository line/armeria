package example.armeria.contextpropagation.rxjava;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class MainService implements HttpService {

    private final WebClient backendClient;

    public MainService(WebClient backendClient) {
        this.backendClient = requireNonNull(backendClient, "backendClient");
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
        final Scheduler contextAwareScheduler = Schedulers.from(ctx.eventLoop());

        // This logic mimics using a blocking method, which would usually be something like a MySQL
        // database query using JDBC.
        final Flowable<Long> fetchNumsFromFakeDb =
                Single.fromCallable(
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
                      .subscribeOn(Schedulers.from(ctx.blockingTaskExecutor()))
                      .flattenAsFlowable(l -> l);

        final Flowable<Long> extractNumsFromRequest =
                Single.fromCompletionStage(req.aggregate())
                               // Unless you know what you're doing, always use subscribeOn with the context
                               // executor to have the context mounted and stay on a single thread to reduce
                               // concurrency issues.
                               .subscribeOn(contextAwareScheduler)
                               .flatMapPublisher(request -> {
                                   // The context is mounted in a thread-local, meaning it is available to all
                                   // logic such as tracing.
                                   assert ServiceRequestContext.current() == ctx;
                                   assert ctx.eventLoop().inEventLoop();

                                   final List<Long> nums = new ArrayList<>();
                                   Arrays.stream(request.path().substring(1).split(",")).forEach(token -> {
                                       nums.add(Long.parseLong(token));
                                   });
                                   Arrays.stream(request.contentUtf8().split(",")).forEach(token -> {
                                       nums.add(Long.parseLong(token));
                                   });

                                   return Flowable.fromIterable(nums);
                               });

        final Single<HttpResponse> response =
                Flowable.concatArrayEager(extractNumsFromRequest, fetchNumsFromFakeDb)
                        // Unless you know what you're doing, always use subscribeOn with the context executor
                        // to have the context mounted and stay on a single thread to reduce concurrency issues.
                        .subscribeOn(contextAwareScheduler)
                        // When concatenating flowables, you should almost always call observeOn with the
                        // context executor because we don't know here whether the subscription is on it or
                        // something like a blocking task executor.
                        .observeOn(contextAwareScheduler)
                        .flatMapSingle(num -> {
                            // The context is mounted in a thread-local, meaning it is available to all logic
                            // such as tracing.
                            assert ServiceRequestContext.current() == ctx;
                            assert ctx.eventLoop().inEventLoop();

                            return Single.fromCompletionStage(backendClient.get("/square/" + num).aggregate());
                        })
                        .map(AggregatedHttpResponse::contentUtf8)
                        .collectInto(new StringBuilder(), (current, item) -> current.append(item).append('\n'))
                        .map(content -> HttpResponse.of(content.toString()))
                        .onErrorReturn(HttpResponse::ofFailure);

        return HttpResponse.from(response.toCompletionStage());
    }
}
