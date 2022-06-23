package com.linecorp.armeria.server.grpc.validation;

import com.google.protobuf.MessageLiteOrBuilder;

interface RequestValidator<T extends MessageLiteOrBuilder> {
    ValidationResult isValid(T request);
}
