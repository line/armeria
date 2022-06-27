package com.linecorp.armeria.server.protobuf;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.internal.server.annotation.OrElseResponseConverterFunction;
import com.linecorp.armeria.protobuf.testing.Messages.SimpleResponse;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

class ProtobufResponseConverterFunctionProviderTest {

    @Test
    void providesOrElseResponseConverterFunctionGivenTypeIsSupported() {
        final ProtobufResponseConverterFunctionProvider provider =
                new ProtobufResponseConverterFunctionProvider();

        final ResponseConverterFunction
                function = provider.createResponseConverterFunction(SimpleResponse.class,
                                                                    new ProtobufResponseConverterFunction());

        assertThat(function).isInstanceOf(OrElseResponseConverterFunction.class);
    }

    @Test
    void returnsNullGivenTypeIsNotSupported() {
        final ProtobufResponseConverterFunctionProvider provider =
                new ProtobufResponseConverterFunctionProvider();

        final ResponseConverterFunction
                function = provider.createResponseConverterFunction(String.class,
                                                                    new ProtobufResponseConverterFunction());

        assertThat(function).isNull();
    }
}
