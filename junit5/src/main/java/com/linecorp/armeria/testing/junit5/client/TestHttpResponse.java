/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.testing.junit5.client;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;

/**
 * A complete HTTP response includes fluent test methods. whose content is readily available as a single {@link HttpData}.
 */
public interface TestHttpResponse extends AggregatedHttpMessage {

    static TestHttpResponse of(AggregatedHttpResponse aggregatedHttpResponse) {
        requireNonNull(aggregatedHttpResponse, "aggregatedHttpResponse");
        return new DefaultTestHttpResponse(aggregatedHttpResponse);
    }

    /**
     * Returns the {@link ResponseHeaders}.
     */
    @Override
    ResponseHeaders headers();

    /**
     * Returns the informational class (1xx) HTTP headers.
     */
    List<ResponseHeaders> informationals();

    /**
     * Returns the {@linkplain HttpHeaderNames#STATUS STATUS} of this response.
     */
    HttpStatus status();

    /**
     * Converts this response into a new complete {@link HttpResponse}.
     *
     * @return the new {@link HttpResponse} converted from this response.
     */
    HttpResponse toHttpResponse();

    /**
     * Creates a new instance of {@link HttpStatusAssert} from status of the http response.
     */
    HttpStatusAssert assertStatus();

    /**
     * Creates a new instance of {@link HttpHeadersAssert} from headers of the http response.
     */
    HttpHeadersAssert assertHeaders();

    /**
     * Creates a new instance of {@link HttpDataAssert} from content of the http response.
     */
    HttpDataAssert assertContent();

    /**
     * Creates a new instance of {@link HttpHeadersAssert} from trailers the http response.
     */
    HttpHeadersAssert assertTrailers();
}
