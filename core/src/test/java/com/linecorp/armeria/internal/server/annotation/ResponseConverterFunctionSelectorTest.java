package com.linecorp.armeria.internal.server.annotation;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

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
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

@SuppressWarnings("ConstantConditions")
class ResponseConverterFunctionSelectorTest {

    private static final ServiceRequestContext ctx = ServiceRequestContext.builder(
            HttpRequest.of(HttpMethod.GET, "/")).build();

    @Test
    void prioritisesSpiDelegatingResponseConverterProvider() throws Exception {
        final ResponseConverterFunction converterFunction =
                ResponseConverterFunctionSelector.responseConverter(
                        TestClassWithDelegatingResponseConverterProvider.class,
                        Collections.singletonList(new MyResponseConverterFunction())
                );

        final HttpResponse response = converterFunction.convertResponse(ctx, null,
                                                                        new TestClassWithDelegatingResponseConverterProvider(),
                                                                        null);

        assertThat(response.aggregate().join().contentUtf8()).isEqualTo("testDelegatingResponse");
    }

    @Test
    void prioritisesPassedInResponseConvertersGivenNoDelegatingResponseConverterProviderAvailable()
            throws Exception {
        final ResponseConverterFunction converterFunction =
                ResponseConverterFunctionSelector.responseConverter(
                        TestClassWithNonDelegatingResponseConverterProvider.class,
                        Collections.singletonList(new MyResponseConverterFunction())
                );

        final HttpResponse response = converterFunction.convertResponse(ctx, null,
                                                                        new TestClassWithNonDelegatingResponseConverterProvider(),
                                                                        null);

        assertThat(response.aggregate().join().contentUtf8()).isEqualTo("my_custom_converter_response");
    }

    @Test
    void usesNonDelegatingSpiResponseConverterGivenNoResponseConverterSpecified() throws Exception {
        final ResponseConverterFunction converterFunction =
                ResponseConverterFunctionSelector.responseConverter(
                        TestClassWithNonDelegatingResponseConverterProvider.class,
                        emptyList()
                );

        final HttpResponse response = converterFunction.convertResponse(ctx, null,
                                                                        new TestClassWithNonDelegatingResponseConverterProvider(),
                                                                        null);

        assertThat(response.aggregate().join().contentUtf8()).isEqualTo("testNonDelegatingResponse");
    }

    private static class MyResponseConverterFunction implements ResponseConverterFunction {
        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx, ResponseHeaders headers,
                                            @Nullable Object result, HttpHeaders trailers) throws Exception {
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT, "my_custom_converter_response");
        }
    }

    static class TestClassWithDelegatingResponseConverterProvider {}
    static class TestClassWithNonDelegatingResponseConverterProvider {}
}
