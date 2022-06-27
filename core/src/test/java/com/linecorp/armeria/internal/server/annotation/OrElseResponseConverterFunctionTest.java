package com.linecorp.armeria.internal.server.annotation;

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
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

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
