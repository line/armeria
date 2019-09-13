package com.linecorp.armeria.spring;

import javax.validation.constraints.NotNull;

import com.google.common.base.MoreObjects;

/**
 * Used as a example request object in {@link AnnotatedServiceRegistrationBean}
 */
public final class AnnotatedExampleRequest {

    public static AnnotatedExampleRequest of(@NotNull String methodName,
                                             @NotNull Object exampleRequest) {
        return new AnnotatedExampleRequest(methodName, exampleRequest);
    }

    private final String methodName;
    private final Object exampleRequest;

    private AnnotatedExampleRequest(String methodName, Object exampleRequest) {
        this.methodName = methodName;
        this.exampleRequest = exampleRequest;
    }

    /**
     * Returns the method name of this {@link AnnotatedExampleRequest}.
     */
    public String getMethodName() {
        return methodName;
    }

    /**
     * Returns the example request of this {@link AnnotatedExampleRequest}.
     */
    public Object getExampleRequest() {
        return exampleRequest;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("methodName", methodName)
                          .add("exampleRequest", exampleRequest)
                          .toString();
    }
}