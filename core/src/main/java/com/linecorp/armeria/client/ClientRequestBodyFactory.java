/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.armeria.client;

import java.util.function.Supplier;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AttributeKey;

/**
 * Holds the well-known {@link AttributeKey} used to supply a reproducible request body to
 * {@code RetryingClient} and {@code RedirectingClient}.
 *
 * <p>For streaming requests larger than about 2 GiB, buffering the body in memory for replay is
 * neither possible (an {@code int} size limit is exceeded) nor desirable. Instead, set a
 * {@link Supplier} that regenerates the request (and thus a fresh body stream) on demand:
 *
 * <pre>{@code
 * final Supplier<HttpRequest> factory =
 *         () -> HttpRequest.of(headers, StreamMessage.of(path));
 * final RequestOptions options =
 *         RequestOptions.builder()
 *                       .attr(ClientRequestBodyFactory.REQUEST_BODY_FACTORY, factory)
 *                       .build();
 * client.execute(factory.get(), options);
 * }</pre>
 *
 * <p>The factory is invoked once per retry attempt or redirect hop beyond the first. The first
 * attempt uses the request passed to {@code execute(...)}. Each request produced by the factory
 * must be equivalent to the original (same method and {@code content-length}); only the body
 * stream should be freshly opened.
 *
 * <p>This attribute is honored only for streaming requests
 * ({@link ExchangeType#isRequestStreaming()}). It is silently ignored for aggregated requests.
 */
@UnstableApi
public final class ClientRequestBodyFactory {

    /**
     * The {@link AttributeKey} of the {@link Supplier} that regenerates the request body for each
     * retry attempt or redirect hop. See {@link ClientRequestBodyFactory} for usage.
     */
    public static final AttributeKey<Supplier<HttpRequest>> REQUEST_BODY_FACTORY =
            AttributeKey.valueOf(ClientRequestBodyFactory.class, "REQUEST_BODY_FACTORY");

    private ClientRequestBodyFactory() {}
}
