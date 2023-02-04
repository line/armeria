package com.linecorp.armeria.spring.web.reactive;

import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.lang.Nullable;

/**
 * A version specific {@link AbstractServerHttpResponse} which implements the APIs that only exists in
 * Spring 6.
 */
abstract class AbstractServerHttpResponseVersionSpecific extends AbstractServerHttpResponse {

    @Nullable
    private HttpStatusCode statusCode;

    AbstractServerHttpResponseVersionSpecific(DataBufferFactory dataBufferFactory) {
        super(dataBufferFactory);
    }

    @Override
    public boolean setStatusCode(@Nullable HttpStatusCode status) {
        if (state() == State.COMMITTED) {
            return false;
        } else {
            statusCode = status;
            return true;
        }
    }

    @Override
    @Nullable
    public HttpStatusCode getStatusCode() {
        return statusCode;
    }

    @Override
    public boolean setRawStatusCode(@Nullable Integer statusCode) {
        return setStatusCode(statusCode != null ? HttpStatusCode.valueOf(statusCode) : null);
    }

    @Deprecated
    @Override
    @Nullable
    public Integer getRawStatusCode() {
        return statusCode != null ? statusCode.value() : null;
    }
}
