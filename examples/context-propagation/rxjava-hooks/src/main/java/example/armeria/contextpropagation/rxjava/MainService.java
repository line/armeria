/*
 * Copyright 2021 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

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
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.internal.functions.Functions;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class MainService implements HttpService {

    private final WebClient backendClient;

    public MainService(WebClient backendClient) {
        this.backendClient = requireNonNull(backendClient, "backendClient");
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
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
                      .flattenAsFlowable(Functions.identity());

        final Flowable<Long> extractNumsFromRequest =
                Single.fromCompletionStage(req.aggregate())
                      .flatMapPublisher(request -> {
                          // The context is mounted in a thread-local, meaning it is available to all
                          // logic such as tracing.
                          assert ServiceRequestContext.current() == ctx;

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

                          return Flowable.fromIterable(nums);
                      });

        final Single<HttpResponse> response =
                Flowable.concatArrayEager(extractNumsFromRequest, fetchNumsFromFakeDb)
                        .flatMapSingle(num -> {
                            // The context is mounted in a thread-local, meaning it is available to all logic
                            // such as tracing.
                            assert ServiceRequestContext.current() == ctx;

                            return Single.fromCompletionStage(backendClient.get("/square/" + num).aggregate());
                        })
                        .map(AggregatedHttpResponse::contentUtf8)
                        .collectInto(new StringBuilder(), (current, item) -> current.append(item).append('\n'))
                        .map(content -> HttpResponse.of(content.toString()))
                        .onErrorReturn(HttpResponse::ofFailure);

        return HttpResponse.from(response.toCompletionStage());
    }
}
