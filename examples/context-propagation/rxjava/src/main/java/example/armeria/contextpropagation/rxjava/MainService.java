package example.armeria.contextpropagation.rxjava;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Uninterruptibles;

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

    private static final Splitter NUM_SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();

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
                            checkState(ServiceRequestContext.current() == ctx);
                            checkState(!ctx.eventLoop().inEventLoop());

                            Uninterruptibles.sleepUninterruptibly(Duration.ofMillis(50));
                            return ImmutableList.of(23L, -23L);
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
                                   checkState(ServiceRequestContext.current() == ctx);
                                   checkState(ctx.eventLoop().inEventLoop());

                                   final List<Long> nums = new ArrayList<>();
                                   for (String token : Iterables.concat(
                                           NUM_SPLITTER.split(request.path().substring(1)),
                                           NUM_SPLITTER.split(request.contentUtf8()))) {
                                       nums.add(Long.parseLong(token));
                                   }

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
                            checkState(ServiceRequestContext.current() == ctx);
                            checkState(ctx.eventLoop().inEventLoop());

                            return Single.fromCompletionStage(backendClient.get("/square/" + num).aggregate());
                        })
                        .map(AggregatedHttpResponse::contentUtf8)
                        .collectInto(new StringBuilder(), (current, item) -> current.append(item).append('\n'))
                        .map(content -> HttpResponse.of(content.toString()))
                        .onErrorReturn(HttpResponse::ofFailure);

        return HttpResponse.from(response.toCompletionStage());
    }
}
