package com.linecorp.armeria.internal.server.annotation;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.annotation.ResponseConverterFunctionSelectorTest.TestClassToConvert1;
import com.linecorp.armeria.internal.server.annotation.ResponseConverterFunctionSelectorTest.TestClassToConvert2;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunctionProvider;

/**
 * For use with ResponseConverterFunctionSelectorTest
 */
public class TestSpiConverter implements ResponseConverterFunctionProvider {

    @Override
    public @Nullable ResponseConverterFunction createResponseConverterFunction(Type responseType,
                                                                               ResponseConverterFunction responseConverter) {
        final Class<?> responseClass = toClass(responseType);
        if (TestClassToConvert1.class.isAssignableFrom(responseClass)
            || TestClassToConvert2.class.isAssignableFrom(responseClass)) {
            return new TestResponseConverterFunction1(responseConverter);
        } else {
            return null;
        }
    }

    @SuppressWarnings({ "ConstantConditions", "ReturnOfNull" })
    private static Class<?> toClass(Type type) {
        if (type instanceof ParameterizedType) {
            return (Class<?>) ((ParameterizedType) type).getRawType();
        } else if (type instanceof Class<?>) {
            return (Class<?>) type;
        } else {
            return null;
        }
    }

    static class TestResponseConverterFunction1 implements ResponseConverterFunction {
        ResponseConverterFunction passedInResponseConverterFunction;

        TestResponseConverterFunction1(ResponseConverterFunction responseConverter) {
            passedInResponseConverterFunction = responseConverter;
        }

        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx, ResponseHeaders headers,
                                            @Nullable Object result, HttpHeaders trailers) throws Exception {
            if (result instanceof TestClassToConvert1) {
                return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT, "testResponse");
            }
            if (result instanceof TestClassToConvert2) {
                // For testing that a converter function is still passed in,
                // because some implementations of the ResponseConverterProvider interface use the passed-in converter function
                return passedInResponseConverterFunction.convertResponse(ctx, headers,
                                                                         "converted_using_passed_in_converter",
                                                                         trailers);
            }
            return ResponseConverterFunction.fallthrough();
        }
    }
}
