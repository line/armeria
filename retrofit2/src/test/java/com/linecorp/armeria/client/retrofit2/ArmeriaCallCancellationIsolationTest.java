/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linecorp.armeria.client.retrofit2;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Invocation;
import retrofit2.Response;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Url;

/**
 * Verifies that Retrofit's thread-local context customizer does not leak cancellation or invocation
 * metadata to independent requests started on the same thread by a decorator or context customizer.
 * Canceling the outer call must abort its HTTP request while the independent request can still succeed.
 */
class ArmeriaCallCancellationIsolationTest {

    enum LaunchLocation {
        DECORATOR, CONTEXT_CUSTOMIZER
    }

    private static final AtomicInteger nextId = new AtomicInteger();
    private static final Map<String, HeldResponse> requests = new ConcurrentHashMap<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(0);
            sb.serviceUnder("/held/", (ctx, req) -> {
                final HeldResponse held = requests.get(req.path());
                assertThat(held).isNotNull();
                req.aggregate().handle((aggregated, cause) -> {
                    if (cause != null) {
                        held.arrived.completeExceptionally(cause);
                    } else {
                        held.body.complete(aggregated.contentUtf8());
                        held.arrived.complete(ctx);
                    }
                    return null;
                });
                return held.response;
            });
        }
    };

    interface Api {
        @GET
        Call<Void> get(@Url String path);

        @POST
        Call<Void> post(@Url String path, @Body RequestBody body);
    }

    static List<Arguments> independentRequests() {
        final List<Arguments> cases = new ArrayList<>();
        for (SessionProtocol protocol : new SessionProtocol[] { SessionProtocol.H1C, SessionProtocol.H2C }) {
            for (LaunchLocation location : LaunchLocation.values()) {
                for (boolean nestedRetrofit : new boolean[] { false, true }) {
                    for (boolean withBody : new boolean[] { false, true }) {
                        for (boolean interrupt : new boolean[] { false, true }) {
                            cases.add(Arguments.of(protocol, location, nestedRetrofit, withBody, interrupt));
                        }
                    }
                }
            }
        }
        return cases;
    }

    @ParameterizedTest
    @MethodSource("independentRequests")
    void cancellingOuterCallDoesNotCancelIndependentRequest(SessionProtocol protocol, LaunchLocation location,
                                                             boolean nestedRetrofit, boolean withBody,
                                                             boolean interrupt) throws Exception {
        final String prefix = "/held/" + nextId.incrementAndGet();
        try (HeldResponse main = new HeldResponse(prefix + "/main");
             HeldResponse side = new HeldResponse(prefix + "/side");
             ClientFactory mainFactory = ClientFactory.builder().build();
             ClientFactory sideFactory = ClientFactory.builder().build()) {
            final CompletableFuture<Invocation> sideInvocation = new CompletableFuture<>();
            final WebClient sideClient = WebClient.builder(protocol, server.httpEndpoint())
                                                  .factory(sideFactory)
                                                  .responseTimeoutMillis(0)
                                                  .decorator((delegate, ctx, req) -> {
                                                      sideInvocation.complete(
                                                              InvocationUtil.getInvocation(ctx.log()));
                                                      return delegate.execute(ctx, req);
                                                  })
                                                  .build();
            final Call<Void> sideCall = ArmeriaRetrofit.builder(sideClient).build()
                                                      .create(Api.class).get(side.path);
            final CompletableFuture<Integer> sideResult = new CompletableFuture<>();
            final Runnable startSide = () -> {
                if (nestedRetrofit) {
                    sideCall.enqueue(callback(sideResult));
                } else {
                    sideClient.get(side.path).aggregate().handle((response, cause) -> {
                        if (cause != null) {
                            sideResult.completeExceptionally(cause);
                        } else {
                            sideResult.complete(response.status().code());
                        }
                        return null;
                    });
                }
            };
            final WebClientBuilder builder = WebClient.builder(protocol, server.httpEndpoint())
                                                      .factory(mainFactory)
                                                      .responseTimeoutMillis(0);
            // Start the side request inline while the outer call's thread-local context customizer is active.
            if (location == LaunchLocation.DECORATOR) {
                builder.decorator((delegate, ctx, req) -> {
                    startSide.run();
                    return delegate.execute(ctx, req);
                });
            } else {
                builder.contextCustomizer(ctx -> startSide.run());
            }
            final Api api = ArmeriaRetrofit.builder(builder.build()).build().create(Api.class);
            final Call<Void> mainCall =
                    withBody ? api.post(main.path,
                                        RequestBody.create(MediaType.parse("text/plain"), "request-body"))
                             : api.get(main.path);
            final CompletableFuture<Integer> mainResult = new CompletableFuture<>();
            final CompletableFuture<Boolean> interrupted = new CompletableFuture<>();
            final Thread caller = new Thread(() -> {
                try {
                    if (interrupt) {
                        mainResult.complete(mainCall.execute().code());
                    } else {
                        mainCall.enqueue(callback(mainResult));
                    }
                } catch (Throwable cause) {
                    mainResult.completeExceptionally(cause);
                } finally {
                    interrupted.complete(Thread.currentThread().isInterrupted());
                }
            });
            caller.setDaemon(true);
            caller.start();
            try {
                final ServiceRequestContext mainServer = main.arrived.get(5, TimeUnit.SECONDS);
                side.arrived.get(5, TimeUnit.SECONDS);
                assertThat(main.body.get(5, TimeUnit.SECONDS)).isEqualTo(withBody ? "request-body" : "");
                assertThat(mainResult).isNotDone();
                assertThat(sideResult).isNotDone();

                if (interrupt) {
                    caller.interrupt();
                } else {
                    mainCall.cancel();
                }
                final Throwable mainCause = mainResult.handle((response, cause) -> cause)
                                                      .get(5, TimeUnit.SECONDS);
                assertThat(mainCause).isInstanceOf(IOException.class);
                if (interrupt) {
                    assertThat(mainCause).isInstanceOf(InterruptedIOException.class);
                    assertThat(interrupted.get(5, TimeUnit.SECONDS)).isTrue();
                }
                mainServer.whenRequestCancelling().get(5, TimeUnit.SECONDS);

                assertThat(side.response.tryWrite(ResponseHeaders.of(HttpStatus.OK))).isTrue();
                side.response.close();
                assertThat(sideResult.get(5, TimeUnit.SECONDS)).isEqualTo(200);
                // A nested Retrofit call must keep its own Invocation; a plain WebClient call must have none.
                final Invocation invocation = sideInvocation.get(5, TimeUnit.SECONDS);
                if (nestedRetrofit) {
                    assertThat(invocation).isNotNull();
                    assertThat(invocation.arguments().get(0)).isEqualTo(side.path);
                } else {
                    assertThat(invocation).isNull();
                }
            } finally {
                mainCall.cancel();
                sideCall.cancel();
                caller.interrupt();
                caller.join(5000);
            }
        }
    }

    private static Callback<Void> callback(CompletableFuture<Integer> result) {
        return new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                result.complete(response.code());
            }

            @Override
            public void onFailure(Call<Void> call, Throwable cause) {
                result.completeExceptionally(cause);
            }
        };
    }

    private static final class HeldResponse implements AutoCloseable {
        final String path;
        final HttpResponseWriter response = HttpResponse.streaming();
        final CompletableFuture<ServiceRequestContext> arrived = new CompletableFuture<>();
        final CompletableFuture<String> body = new CompletableFuture<>();

        HeldResponse(String path) {
            this.path = path;
            requests.put(path, this);
        }

        @Override
        public void close() {
            response.abort();
            requests.remove(path);
        }
    }
}
