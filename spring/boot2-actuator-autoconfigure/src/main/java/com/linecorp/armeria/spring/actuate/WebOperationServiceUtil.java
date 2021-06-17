package com.linecorp.armeria.spring.actuate;

import org.springframework.boot.actuate.endpoint.OperationArgumentResolver;
import org.springframework.boot.actuate.endpoint.ProducibleOperationArgumentResolver;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;

final class WebOperationServiceUtil {

    static OperationArgumentResolver acceptHeadersResolver(HttpHeaders headers) {
        return new ProducibleOperationArgumentResolver(() -> headers.getAll(HttpHeaderNames.ACCEPT));
    }

    private WebOperationServiceUtil() {}
}
