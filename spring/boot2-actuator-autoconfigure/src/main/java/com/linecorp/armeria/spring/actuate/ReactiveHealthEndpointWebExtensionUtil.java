/*
 * Copyright 2022 LINE Corporation
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
package com.linecorp.armeria.spring.actuate;

import static com.linecorp.armeria.spring.actuate.WebOperationService.handleResult;

import org.reactivestreams.Publisher;
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.ServiceRequestContext;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

final class ReactiveHealthEndpointWebExtensionUtil {

    static HttpResponse handleMaybeReactorResult(SimpleHttpCodeStatusMapper statusMapper,
                                                 ServiceRequestContext ctx, Object result,
                                                 HttpMethod method) throws Throwable {
        if (result instanceof Flux) {
            return handlePublisher(statusMapper, ctx, ((Flux<?>) result).collectList(), method);
        }

        if (result instanceof Publisher) {
            return handlePublisher(statusMapper, ctx, (Publisher<?>) result, method);
        }

        return handleResult(statusMapper, ctx, result, method);
    }

    private static HttpResponse handlePublisher(SimpleHttpCodeStatusMapper statusMapper,
                                                ServiceRequestContext ctx, Publisher<?> result,
                                                HttpMethod method) {
        final Mono<HttpResponse> monoResponse =
                Mono.from(result).map(result0 -> {
                        try {
                            return handleResult(statusMapper, ctx, result0, method);
                        } catch (Throwable e) {
                            return Exceptions.throwUnsafely(e);
                        }
                    })
                    .onErrorResume(InvalidEndpointRequestException.class,
                                   ex -> Mono.just(HttpResponse.of(HttpStatus.BAD_REQUEST,
                                                                   MediaType.PLAIN_TEXT_UTF_8,
                                                                   ex.getReason())))
                    .defaultIfEmpty((method != HttpMethod.GET) ? HttpResponse.of(204) : HttpResponse.of(404));
        return HttpResponse.of(StreamMessage.concat(monoResponse));
    }

    private ReactiveHealthEndpointWebExtensionUtil() {}
}
