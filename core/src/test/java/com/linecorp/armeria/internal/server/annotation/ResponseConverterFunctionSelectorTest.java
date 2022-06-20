package com.linecorp.armeria.internal.server.annotation;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.HttpResult;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

/**
 * This test uses TestSpiConverter
 * This converter is automatically loaded via the configuration specified in the file:
 * com.linecorp.armeria.server.annotation.ResponseConverterFunctionProvider, inside the META-INF.services folder
 */
class ResponseConverterFunctionSelectorTest {

    private static final ServiceRequestContext CONTEXT = ServiceRequestContext.builder(
            HttpRequest.of(HttpMethod.GET, "/")).build();
    private static final ResponseHeaders HEADERS = ResponseHeaders.of(HttpStatus.OK);
    private static final HttpHeaders TRAILERS = HttpHeaders.of();

    @Test
    void prioritisesPassedInResponseConverters() throws Exception {
        // uses provided converter instead of default String converter
        final ResponseConverterFunction converterFunction =
                ResponseConverterFunctionSelector.responseConverter(
                        getMethod("testEndpointReturningString"),
                        Collections.singletonList(new MyResponseConverterFunctionProvider())
                );

        final HttpResponse response = converterFunction.convertResponse(CONTEXT, HEADERS,
                                                                        "my_string",
                                                                        TRAILERS);

        assertThat(response.aggregate().join().contentUtf8()).isEqualTo("my_response");
    }

    @Test
    void usesSpiResponseConverterGivenNoResponseConverterSpecified() throws Exception {
        final ResponseConverterFunction converterFunction =
                ResponseConverterFunctionSelector.responseConverter(
                        getMethod("testEndpoint1"),
                        emptyList()
                );

        final HttpResponse response = converterFunction.convertResponse(CONTEXT, HEADERS,
                                                                        new TestClassToConvert1(),
                                                                        TRAILERS);

        assertThat(response.aggregate().join().contentUtf8()).isEqualTo("testResponse");
    }

    @Test
    void providesConverterFunctionForSpiProviders() throws Exception {
        // ResponseConverterFunctionSelector should provide a ResponseConverterFunction to ResponseConverterFunctionProviders
        final ResponseConverterFunction converterFunction =
                ResponseConverterFunctionSelector.responseConverter(
                        getMethod("testEndpoint2"),
                        emptyList()
                );

        final HttpResponse response = converterFunction.convertResponse(CONTEXT, HEADERS,
                                                                        new TestClassToConvert2(),
                                                                        TRAILERS);

        assertThat(response.aggregate().join().contentUtf8()).isEqualTo("converted_using_passed_in_converter");
    }

    @Test
    void usesDefaultStringConverterGivenNoResponseConverterSpecifiedNorSpiConverterFound() throws Exception {
        final ResponseConverterFunction converterFunction =
                ResponseConverterFunctionSelector.responseConverter(
                        getMethod("testEndpointReturningString"),
                        emptyList()
                );

        final HttpResponse response = converterFunction.convertResponse(CONTEXT, HEADERS, "my_string",
                                                                        TRAILERS);

        assertThat(response.aggregate().join().contentUtf8()).isEqualTo("my_string");
    }

    private static Method getMethod(String methodName) throws NoSuchMethodException {
        return TestService.class.getMethod(methodName);
    }

    private static class MyResponseConverterFunctionProvider implements ResponseConverterFunction {
        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx, ResponseHeaders headers,
                                            @Nullable Object result, HttpHeaders trailers) throws Exception {
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT, "my_response");
        }
    }

    @SuppressWarnings("unused")
    private interface TestService {
        HttpResult<TestClassToConvert1> testEndpoint1();

        HttpResult<TestClassToConvert2> testEndpoint2();

        HttpResult<String> testEndpointReturningString();
    }

    static class TestClassToConvert1 {}

    static class TestClassToConvert2 {}
}
