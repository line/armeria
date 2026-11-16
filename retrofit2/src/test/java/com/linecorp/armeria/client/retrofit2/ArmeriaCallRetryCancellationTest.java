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
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.retrofit2;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

class ArmeriaCallRetryCancellationTest {

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void cancellationAbortsSecondAttemptBeforeServerResponse(boolean interrupt) throws Exception {
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger attempts = new AtomicInteger();
        final CompletableFuture<Void> secondArrived = new CompletableFuture<>();
        final CompletableFuture<HttpResponse> serverResponse = new CompletableFuture<>();
        final CompletableFuture<Throwable> secondTransportDone = new CompletableFuture<>();
        final Server server = Server.builder().http(0).service("/", (ctx, req) -> {
            if (requests.incrementAndGet() == 1) {
                return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE);
            }
            secondArrived.complete(null);
            return HttpResponse.of(serverResponse);
        }).build();
        server.start().join();

        try (ClientFactory factory = ClientFactory.builder().build()) {
            final WebClient client = WebClient.builder(SessionProtocol.H2C,
                                                       Endpoint.of("127.0.0.1", server.activeLocalPort()))
                                              .factory(factory)
                                              .responseTimeoutMillis(0)
                                              .decorator((delegate, ctx, req) -> {
                                                  final int attempt = attempts.incrementAndGet();
                                                  final HttpResponse response = delegate.execute(ctx, req);
                                                  if (attempt == 2) {
                                                      response.whenComplete().handle((unused, cause) -> {
                                                          secondTransportDone.complete(cause);
                                                          return null;
                                                      });
                                                  }
                                                  return response;
                                              })
                                              .decorator(RetryingClient.newDecorator(
                                                      RetryRule.onServerErrorStatus(Backoff.withoutDelay())))
                                              .build();
            final Call call = ArmeriaRetrofit.builder(client).build().callFactory().newCall(
                    new Request.Builder().url("http://127.0.0.1:" + server.activeLocalPort() + '/').build());
            final CompletableFuture<Throwable> result = new CompletableFuture<>();
            final CompletableFuture<Boolean> interrupted = new CompletableFuture<>();
            final Thread thread = new Thread(() -> {
                try (Response ignored = call.execute()) {
                    result.complete(null);
                } catch (Throwable cause) {
                    result.complete(cause);
                } finally {
                    interrupted.complete(Thread.currentThread().isInterrupted());
                }
            });
            thread.setDaemon(true);

            try {
                if (interrupt) {
                    thread.start();
                } else {
                    call.enqueue(new Callback() {
                        @Override
                        public void onFailure(Call call, IOException cause) {
                            result.complete(cause);
                        }

                        @Override
                        public void onResponse(Call call, Response response) {
                            response.close();
                            result.complete(null);
                        }
                    });
                }

                secondArrived.get(5, TimeUnit.SECONDS);
                if (interrupt) {
                    thread.interrupt();
                    assertThat(result.get(5, TimeUnit.SECONDS)).isInstanceOf(InterruptedIOException.class);
                    assertThat(interrupted.get(5, TimeUnit.SECONDS)).isTrue();
                } else {
                    call.cancel();
                    assertThat(result.get(5, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
                }
                assertThat(secondTransportDone.get(5, TimeUnit.SECONDS)).isNotNull();
                assertThat(call.isCanceled()).isTrue();
                assertThat(attempts).hasValue(2);
                assertThat(requests).hasValue(2);
                assertThat(serverResponse).isNotDone();
            } finally {
                call.cancel();
                thread.join(5000);
            }
        } finally {
            serverResponse.complete(HttpResponse.of(HttpStatus.OK));
            server.stop().join();
        }
    }
}
