package com.linecorp.armeria.spring;

import javax.validation.constraints.NotNull;

import com.google.common.base.MoreObjects;

/**
 * Used as a example request object in {@link GrpcServiceRegistrationBean}
 */
public final class GrpcExampleRequest {

    public static GrpcExampleRequest of(@NotNull String serviceType,
                                        @NotNull String methodName,
                                        @NotNull Object exampleRequest) {
        return new GrpcExampleRequest(serviceType, methodName, exampleRequest);
    }

    private final String serviceType;
    private final String methodName;
    private final Object exampleRequest;

    private GrpcExampleRequest(String serviceType, String methodName, Object exampleRequest) {
        this.serviceType = serviceType;
        this.methodName = methodName;
        this.exampleRequest = exampleRequest;
    }

    /**
     * Returns the service type of this {@link GrpcExampleRequest}.
     */
    public String getServiceType() {
        return serviceType;
    }

    /**
     * Returns the method name of this {@link GrpcExampleRequest}.
     */
    public String getMethodName() {
        return methodName;
    }

    /**
     * Returns the example request of this {@link GrpcExampleRequest}.
     */
    public Object getExampleRequest() {
        return exampleRequest;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("serviceType", serviceType)
                          .add("methodName", methodName)
                          .add("exampleRequest", exampleRequest)
                          .toString();
    }
}