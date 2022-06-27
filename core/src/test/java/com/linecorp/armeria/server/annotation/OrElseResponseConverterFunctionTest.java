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

package com.linecorp.armeria.server.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;

class OrElseResponseConverterFunctionTest {

    private static final ServiceRequestContext ctx = ServiceRequestContext.builder(
            HttpRequest.of(HttpMethod.GET, "/")).build();

    private static final ResponseHeaders HEADERS = ResponseHeaders.of(HttpStatus.OK);
    private static final HttpHeaders TRAILERS = HttpHeaders.of();

    @Test
    void shouldPrioritiseUsingFirstConverter() throws Exception {
        final TestResponseConverterFunction converter1 = new TestResponseConverterFunction();
        final OrElseResponseConverterFunction orElseConverter = converter1.orElse(
                new TestAlwaysFallThroughResponseConverterFunction());

        final HttpResponse response = orElseConverter.convertResponse(ctx, HEADERS, "test", TRAILERS);

        assertThat(getContent(response)).isEqualTo("converted_using_test_response_converter");
    }

    @Test
    void shouldFallbackToSecondConverterIfFirstConverterFails() throws Exception {
        final TestAlwaysFallThroughResponseConverterFunction
                fallThroughConverter = new TestAlwaysFallThroughResponseConverterFunction();
        final OrElseResponseConverterFunction orElseConverter = fallThroughConverter.orElse(
                new TestResponseConverterFunction());

        final HttpResponse response = orElseConverter.convertResponse(ctx, HEADERS, "test", TRAILERS);

        assertThat(getContent(response)).isEqualTo("converted_using_test_response_converter");
    }

    private static String getContent(HttpResponse response) throws ExecutionException, InterruptedException {
        return response.aggregate().get().contentUtf8();
    }

    private static class TestResponseConverterFunction
            implements ResponseConverterFunction {

        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx, ResponseHeaders headers,
                                            @Nullable Object result, HttpHeaders trailers) throws Exception {
            return HttpResponse.of("converted_using_test_response_converter");
        }
    }

    private static class TestAlwaysFallThroughResponseConverterFunction
            implements ResponseConverterFunction {

        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx, ResponseHeaders headers,
                                            @Nullable Object result, HttpHeaders trailers) throws Exception {
            // always fall through
            return ResponseConverterFunction.fallthrough();
        }
    }
}
