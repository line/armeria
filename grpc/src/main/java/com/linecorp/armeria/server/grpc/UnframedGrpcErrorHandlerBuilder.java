package com.linecorp.armeria.server.grpc;

import static java.util.Objects.requireNonNull;

import java.util.Set;

import org.curioswitch.common.protobuf.json.MessageMarshaller;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.Nullable;

public final class UnframedGrpcErrorHandlerBuilder {

    private static final Set<UnframedGrpcErrorResponseType> DEFAULT_RESPONSE_TYPES =
            ImmutableSet.copyOf(UnframedGrpcErrorResponseType.values());

    @Nullable
    private UnframedGrpcStatusMappingFunction statusMappingFunction;

    @Nullable
    private MessageMarshaller jsonMarshaller;

    private Set<UnframedGrpcErrorResponseType> responseTypes = DEFAULT_RESPONSE_TYPES;

    public UnframedGrpcErrorHandlerBuilder jsonMarshaller(MessageMarshaller jsonMarshaller) {
        this.jsonMarshaller = requireNonNull(jsonMarshaller, "jsonMarshaller");
        return this;
    }

    public UnframedGrpcErrorHandlerBuilder statusMappingFunction(
            UnframedGrpcStatusMappingFunction statusMappingFunction) {
        this.statusMappingFunction = requireNonNull(statusMappingFunction, "statusMappingFunction");
        return this;
    }

    public UnframedGrpcErrorHandlerBuilder responseTypes(UnframedGrpcErrorResponseType... responseTypes) {
        this.responseTypes = ImmutableSet.copyOf(requireNonNull(responseTypes, "responseTypes"));
        return this;
    }

    public UnframedGrpcErrorHandler build() {
        final UnframedGrpcStatusMappingFunction statusMappingFunction =
                this.statusMappingFunction != null ? this.statusMappingFunction
                                                   : UnframedGrpcStatusMappingFunction.of();
        if(responseTypes == ImmutableSet.of(UnframedGrpcErrorResponseType.JSON)) {
            if(jsonMarshaller != null) {
                return UnframedGrpcErrorHandler.ofJson(statusMappingFunction, jsonMarshaller);
            } else {
                return UnframedGrpcErrorHandler.ofJson(statusMappingFunction);
            }
        }
        if(responseTypes == ImmutableSet.of(UnframedGrpcErrorResponseType.PLAINTEXT)) {
            return UnframedGrpcErrorHandler.ofPlainText(statusMappingFunction);
        }
        if(jsonMarshaller != null) {
            return UnframedGrpcErrorHandler.of(statusMappingFunction, jsonMarshaller);
        }
        return UnframedGrpcErrorHandler.of(statusMappingFunction);
    }
}
