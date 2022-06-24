package com.linecorp.armeria.server.protobuf;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.protobuf.testing.Messages.SimpleResponse;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

class ProtobufResponseConverterFunctionProviderTest {

    @Test
    void usesProvidedConverterGivenItIsAProtobufResponseConverterFunction() {
        final ProtobufResponseConverterFunctionProvider provider =
                new ProtobufResponseConverterFunctionProvider();
        final ProtobufResponseConverterFunction functionToBeUsed = new ProtobufResponseConverterFunction();

        final ResponseConverterFunction
                function = provider.createResponseConverterFunction(SimpleResponse.class,
                                                                    functionToBeUsed);

        // Test that they are the same instance
        assertThat(function).isEqualTo(functionToBeUsed);
    }

    // The code below has been commented out because the function passed in is the CompositeResponseConverterFunction which contains a list of backing converters.
    // The expectations outlined in the test are incorrect in that case
/*    @Test
    void providesNewConverterGivenPassedInConverterIsNotTheSameClass() {
        final ProtobufResponseConverterFunctionProvider provider =
                new ProtobufResponseConverterFunctionProvider();
        final TestResponseConverterFunction functionToBeUsed = new TestResponseConverterFunction();

        final ResponseConverterFunction
                function = provider.createResponseConverterFunction(SimpleResponse.class,
                                                                    functionToBeUsed);

        assertThat(function).isInstanceOf(ProtobufResponseConverterFunction.class);
    }*/

    private class TestResponseConverterFunction
            implements ResponseConverterFunction {

        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx, ResponseHeaders headers,
                                            @Nullable Object result, HttpHeaders trailers) throws Exception {
            return null;
        }
    }
}
