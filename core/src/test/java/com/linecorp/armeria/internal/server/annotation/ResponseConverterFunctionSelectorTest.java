package com.linecorp.armeria.internal.server.annotation;

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

@SuppressWarnings("ConstantConditions")
class ResponseConverterFunctionSelectorTest {

    private static final ServiceRequestContext ctx = ServiceRequestContext.builder(
            HttpRequest.of(HttpMethod.GET, "/")).build();

    @Test
    void prioritisesPassedInResponseConverters() throws Exception {
        final ResponseConverterFunction converterFunction =
                ResponseConverterFunctionSelector.responseConverter(
                        getMethod("testEndpoint"),
                        Collections.singletonList(new MyResponseConverterFunctionProvider())
                );

        final HttpResponse response = converterFunction.convertResponse(ctx, null, new TestClassToConvert(),
                                                                        null);

        assertThat(response.aggregate().join().contentUtf8()).isEqualTo("my_response");
    }

    @Test
    void usesSpiResponseConverterGivenNoResponseConverterSpecified() throws Exception {
        final ResponseConverterFunction converterFunction =
                ResponseConverterFunctionSelector.responseConverter(
                        getMethod("testEndpoint"),
                        Collections.emptyList()
                );

        final HttpResponse response = converterFunction.convertResponse(ctx, null, new TestClassToConvert(),
                                                                        null);

        assertThat(response.aggregate().join().contentUtf8()).isEqualTo("testResponse");
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

    @FunctionalInterface
    @SuppressWarnings("unused")
    private interface TestService {
        HttpResult<TestClassToConvert> testEndpoint();
    }

    static class TestClassToConvert {}
}
