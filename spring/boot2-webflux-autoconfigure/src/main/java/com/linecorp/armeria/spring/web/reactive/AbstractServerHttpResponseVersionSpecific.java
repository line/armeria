package com.linecorp.armeria.spring.web.reactive;

import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpStatus;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A version specific {@link AbstractServerHttpResponse} which implements the APIs that only exists in
 * Spring 6.
 */
abstract class AbstractServerHttpResponseVersionSpecific extends AbstractServerHttpResponse {

    @Nullable
    private Integer statusCode;

    AbstractServerHttpResponseVersionSpecific( DataBufferFactory dataBufferFactory) {
        super(dataBufferFactory);
    }

    @Override
    public boolean setStatusCode(@Nullable HttpStatus status) {
        if (state() == State.COMMITTED) {
            return false;
        }
        else {
            statusCode = status != null ? status.value() : null;
            return true;
        }
    }

    @Override
    @Nullable
    public HttpStatus getStatusCode() {
        return statusCode != null ? HttpStatus.resolve(statusCode) : null;
    }

    @Override
    public boolean setRawStatusCode(@Nullable Integer statusCode) {
        if (state() == State.COMMITTED) {
            return false;
        }
        else {
            this.statusCode = statusCode;
            return true;
        }
    }

    @Override
    @Nullable
    public Integer getRawStatusCode() {
        return statusCode;
    }

}
