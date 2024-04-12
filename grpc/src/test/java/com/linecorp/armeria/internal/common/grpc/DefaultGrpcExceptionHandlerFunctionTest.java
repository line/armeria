package com.linecorp.armeria.internal.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.client.circuitbreaker.FailFastException;

import io.grpc.Status;

class DefaultGrpcExceptionHandlerFunctionTest {

    @Test
    void failFastExceptionToUnavailableCode() {
        assertThat(DefaultGrpcExceptionHandlerFunction
                           .fromThrowable(new FailFastException(CircuitBreaker.ofDefaultName()))
                           .getCode())
                .isEqualTo(Status.Code.UNAVAILABLE);
    }
}