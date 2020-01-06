package example.armeria.contextpropagation.rxjava;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import net.javacrumbs.futureconverter.java8rx2.FutureConverter;

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

import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;

public class MainService implements HttpService {

    private static final Splitter NUM_SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();

    private final WebClient backendClient;

    public MainService(WebClient backendClient) {
        this.backendClient = requireNonNull(backendClient, "backendClient");
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
        // This logic mimics using a blocking method, which would usually be something like a MySQL
        // database query using JDBC.
        final Observable<Long> fetchFromFakeDb =
                Single.fromCallable(
                        () -> {
                            // The context is mounted in a thread-local, meaning it is available to all
                            // logic such as tracing.
                            checkState(ServiceRequestContext.current() == ctx);

                            Uninterruptibles.sleepUninterruptibly(Duration.ofMillis(50));
                            return ImmutableList.of(23L, -23L);
                        })
                      // Always run blocking logic on the blocking task executor. By using
                      // ServiceRequestContext.blockingTaskExecutor, you also ensure the context is mounted
                      // inside the logic (e.g., your DB call will be traced!).
                      .subscribeOn(Schedulers.from(ctx.blockingTaskExecutor()))
                      .flattenAsObservable(l -> l);

        final Single<HttpResponse> response = FutureConverter
                .toSingle(req.aggregate())
                .flatMapObservable(request -> {
                    // The context is mounted in a thread-local, meaning it is available to all logic
                    // such as tracing.
                    checkState(ServiceRequestContext.current() == ctx);

                    final List<Long> nums = new ArrayList<>();
                    for (String token : Iterables.concat(
                            NUM_SPLITTER.split(request.path().substring(1)),
                            NUM_SPLITTER.split(request.contentUtf8()))) {
                        nums.add(Long.parseLong(token));
                    }

                    return Observable.fromIterable(nums);
                })
                .mergeWith(fetchFromFakeDb)
                .flatMapSingle(num -> {
                    // The context is mounted in a thread-local, meaning it is available to all logic
                    // such as tracing.
                    checkState(ServiceRequestContext.current() == ctx);

                    return FutureConverter.toSingle(backendClient.get("/square/" + num).aggregate());
                })
                .map(AggregatedHttpResponse::contentUtf8)
                .collectInto(new StringBuilder(), (current, item) -> current.append(item).append('\n'))
                .map(content -> HttpResponse.of(content.toString()))
                .onErrorReturn(HttpResponse::ofFailure);

        return HttpResponse.from(FutureConverter.toCompletableFuture(response));
    }
}
