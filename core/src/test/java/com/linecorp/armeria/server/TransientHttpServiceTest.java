/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class TransientHttpServiceTest {

    private static final AtomicBoolean sanitizerCalled = new AtomicBoolean();
    private static final AtomicBoolean serviceCalled = new AtomicBoolean();

    private static final BiFunction<RequestContext, Throwable, Object> sanitizer =
            (requestContext, cause) -> {
                sanitizerCalled.set(true);
                return cause;
            };

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final HttpService noResponseService = (ctx, req) -> {
                serviceCalled.set(true);
                return HttpResponse.streaming();
            };
            sb.service("/", noResponseService.decorate(TransientHttpService.newDecorator()));
            sb.decorator(LoggingService.builder()
                                       .failureResponseLogLevel(LogLevel.DEBUG)
                                       .responseCauseSanitizer(sanitizer)
                                       .newDecorator());
        }
    };

    @Test
    void shouldLogServiceResponseIfExceptionIsRaised() throws InterruptedException {
        final ClientFactory factory = ClientFactory.builder().build();
        final WebClient client = WebClient.builder(server.httpUri()).factory(factory).build();
        final CompletableFuture<AggregatedHttpResponse> aggregate = client.get("/").aggregate();
        await().untilAtomic(serviceCalled, Matchers.is(true));
        // Close factory to arbitrarily close the connection.
        factory.close();
        // If the sanitizer is called, it means that the service response is logged properly.
        await().untilAtomic(sanitizerCalled, Matchers.is(true));
        assertThatThrownBy(aggregate::join).hasCauseInstanceOf(ClosedStreamException.class);
    }

    @Test
    void extractTransientServiceOptions() {
        final HttpService fooService = (ctx, req) -> HttpResponse.of("foo");
        final HttpService wrapped = fooService.decorate(
                TransientHttpService.newDecorator(TransientServiceOption.WITH_ACCESS_LOGGING));

        @SuppressWarnings("rawtypes")
        final TransientService transientService = wrapped.as(TransientService.class);
        assertThat(transientService).isNotNull();

        @SuppressWarnings("unchecked")
        final Set<TransientServiceOption> transientServiceOptions =
                (Set<TransientServiceOption>) transientService.transientServiceOptions();
        assertThat(transientServiceOptions).containsExactly(TransientServiceOption.WITH_ACCESS_LOGGING);
    }
}
