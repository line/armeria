package com.linecorp.armeria.internal.common.hessian;

/**
 * Hessian for NoSuchMethodException hessian exception.
 *
 * @author eisig
 */
public class HessianNoSuchMethodException extends HessianFaultException {

    /**
     * default NoSuchMethodException code.
     */
    public HessianNoSuchMethodException(String message) {
        this(message, "NoSuchMethodException");
    }

    /**
     * create exception with code.
     */
    public HessianNoSuchMethodException(String message, String code) {
        super(message, code, null);
    }

}
