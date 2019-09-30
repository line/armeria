/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.server.healthcheck;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

import com.google.common.base.Ascii;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A {@link HttpHealthCheckService} which allows overriding its status via a PUT request.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * Server server = Server.builder()
 *                       .serviceUnder("/health", new ManagedHttpHealthCheckService())
 *                       .build();
 * }</pre>
 *
 * <p>Default config uses content on or off. You can also use your own path matching.
 * <pre>{@code
 * > Server server =
 * >             Server.builder()
 * >                   .serviceUnder("health", new ManagedHttpHealthCheckService() {
 * >                         @Override
 * >                         public CompletionStage<Optional<Boolean>> mode(HttpRequest req) {
 * >                            return CompletableFuture.completedFuture(Optional.empty());
 * >                        }
 * >                   })
 * >                   .build();
 * }</pre>
 *
 * @deprecated Use {@link HealthCheckService}.
 */
@Deprecated
public class ManagedHttpHealthCheckService extends HttpHealthCheckService {
    private static final AggregatedHttpResponse TURN_ON_RES = AggregatedHttpResponse
            .of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, HttpData.ofUtf8("Set healthy."));
    private static final AggregatedHttpResponse TURN_OFF_RES = AggregatedHttpResponse
            .of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, HttpData.ofUtf8("Set unhealthy."));
    private static final AggregatedHttpResponse BAD_REQUEST_RES = AggregatedHttpResponse
            .of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8, HttpData.ofUtf8("Not supported."));

    @Override
    protected HttpResponse doPut(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return HttpResponse.from(
                updateHealthStatus(ctx, req).thenApply(HttpResponse::of)
                                            .exceptionally(HttpResponse::ofFailure));
    }

    /**
     * Updates health status using the specified {@link HttpRequest}.
     */
    private CompletionStage<AggregatedHttpResponse> updateHealthStatus(
            ServiceRequestContext ctx, HttpRequest req) {
        return mode(ctx, req).thenApply(mode -> {
            if (!mode.isPresent()) {
                return BAD_REQUEST_RES;
            }

            final boolean isHealthy = mode.get();

            serverHealth.setHealthy(isHealthy);

            return isHealthy ? TURN_ON_RES : TURN_OFF_RES;
        });
    }

    /**
     * Judge the turning mode.
     * True means turn on, False is turn off.
     * If not present, Not supported.
     * Default implementation is check content is on/off for PUT method
     *
     * @param req HttpRequest
     */
    protected CompletionStage<Optional<Boolean>> mode(@SuppressWarnings("unused") ServiceRequestContext ctx,
                                                      HttpRequest req) {
        return req.aggregate()
                  .thenApply(AggregatedHttpRequest::content)
                  .thenApply(HttpData::toStringAscii)
                  .thenApply(content -> {
                      switch (Ascii.toUpperCase(content)) {
                          case "ON":
                              return Optional.of(true);
                          case "OFF":
                              return Optional.of(false);
                          default:
                              return Optional.empty();
                      }
                  });
    }
}
