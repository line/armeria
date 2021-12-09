package com.linecorp.armeria.internal.common.hessian;

import javax.annotation.Nullable;

/**
 * Hessian fault exception
 *
 * @author eisig
 * @see com.caucho.hessian.io.Hessian2Output#writeFault(String, String, Object)
 *
 */
public class HessianFaultException extends RuntimeException {

    private final String code;

    @Nullable
    private final Object detail;

    public HessianFaultException(String message, String code, @Nullable Object detail) {
        super(message);
        this.code = code;
        this.detail = detail;
    }

    public String getCode() {
        return code;
    }

    @Nullable
    public Object getDetail() {
        return detail;
    }

}
