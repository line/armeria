/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.grpc;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.server.grpc.HttpJsonTranscodingQueryParamMatchRule.ORIGINAL_FIELD;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.AnnotationsProto;
import com.google.api.FieldBehavior;
import com.google.api.FieldBehaviorProto;
import com.google.api.HttpRule;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.Duration;
import com.google.protobuf.ExtensionLite;
import com.google.protobuf.FieldMask;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.ListValue;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.Value;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingService.Field;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingService.TranscodingSpec;

import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoMethodDescriptorSupplier;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;
import io.netty.util.internal.StringUtil;

final class HttpJsonTranscodingServiceBuilder {

    private static final Logger logger = LoggerFactory.getLogger(HttpJsonTranscodingServiceBuilder.class);

    private final Map<Descriptors.MethodDescriptor, HttpRule> httpRules = new HashMap<>();

    private final GrpcService delegate;
    private final Map<String, GrpcMethod> methods;

    private final HttpJsonTranscodingOptions options;

    HttpJsonTranscodingServiceBuilder(GrpcService delegate,
                                      Map<String, GrpcMethod> methods,
                                      HttpJsonTranscodingOptions options) {
        this.delegate = delegate;
        this.methods = methods;
        this.options = options;
    }

    static HttpJsonTranscodingServiceBuilder of(GrpcService delegate, HttpJsonTranscodingOptions options) {
        final Map<String, GrpcMethod> methods = new HashMap<>();
        for (ServerServiceDefinition serviceDefinition : delegate.services()) {
            final Descriptors.ServiceDescriptor serviceDesc = serviceDescriptor(serviceDefinition);
            if (serviceDesc == null) {
                continue;
            }

            for (ServerMethodDefinition<?, ?> methodDefinition : serviceDefinition.getMethods()) {
                final Descriptors.MethodDescriptor methodDesc = methodDescriptor(methodDefinition);
                if (methodDesc == null) {
                    continue;
                }

                methods.put(methodDesc.getFullName(), new GrpcMethod(methodDefinition, methodDesc));
            }
        }
        final HttpJsonTranscodingServiceBuilder builder = new HttpJsonTranscodingServiceBuilder(delegate,
                                                                                                methods,
                                                                                                options);
        if (!options.ignoreProtoHttpRule()) {
            for (GrpcMethod method : methods.values()) {
                final MethodOptions methodOptions = method.descriptor.getOptions();
                if (!methodOptions.hasExtension((ExtensionLite<MethodOptions, ?>) AnnotationsProto.http)) {
                    continue;
                }

                final HttpRule httpRule = methodOptions.getExtension(
                        (ExtensionLite<MethodOptions, HttpRule>) AnnotationsProto.http);
                builder.registerHttpRule(method.descriptor, httpRule);
            }
        }
        for (HttpRule additionalRule : options.additionalHttpRules()) {
            final GrpcMethod method = methods.get(additionalRule.getSelector());
            if (method == null) {
                throw new IllegalArgumentException(
                        "No such method for the additional HttpRule: " + additionalRule.getSelector());
            }
            builder.registerHttpRule(method.descriptor, additionalRule);
        }
        return builder;
    }

    @Nullable
    private static Descriptors.ServiceDescriptor serviceDescriptor(ServerServiceDefinition serviceDefinition) {
        @Nullable
        final Object desc = serviceDefinition.getServiceDescriptor().getSchemaDescriptor();
        if (desc instanceof ProtoServiceDescriptorSupplier) {
            return ((ProtoServiceDescriptorSupplier) desc).getServiceDescriptor();
        }
        return null;
    }

    @Nullable
    private static MethodDescriptor methodDescriptor(ServerMethodDefinition<?, ?> methodDefinition) {
        @Nullable
        final Object desc = methodDefinition.getMethodDescriptor().getSchemaDescriptor();
        if (desc instanceof ProtoMethodDescriptorSupplier) {
            return ((ProtoMethodDescriptorSupplier) desc).getMethodDescriptor();
        }
        return null;
    }

    private static Map<String, Field> buildFields(Descriptor desc,
                                                  List<String> parentNames,
                                                  String namePrefix,
                                                  Set<Descriptor> visitedTypes,
                                                  HttpJsonTranscodingQueryParamMatchRule currentMatchRule,
                                                  Set<HttpJsonTranscodingQueryParamMatchRule> matchRules) {
        final ImmutableMap.Builder<String, Field> builder = ImmutableMap.builder();
        for (FieldDescriptor field : desc.getFields()) {
            final JavaType type = field.getJavaType();
            final boolean isRequired = hasRequiredFieldBehavior(field);
            final String fieldName;
            switch (currentMatchRule) {
                case ORIGINAL_FIELD:
                    fieldName = field.getName();
                    break;
                case LOWER_CAMEL_CASE:
                    fieldName = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, field.getName());
                    break;
                case JSON_NAME:
                    if (field.toProto().hasJsonName()) {
                        fieldName = field.toProto().getJsonName();
                    } else {
                        fieldName = null;
                    }
                    break;
                default:
                    throw new Error("Should never reach here");
            }
            if (fieldName == null) {
                // No matching name is found.
                continue;
            }

            final String key;
            if (namePrefix.isEmpty()) {
                key = fieldName;
            } else {
                key = namePrefix + '.' + fieldName;
            }
            switch (type) {
                case INT:
                case LONG:
                case FLOAT:
                case DOUBLE:
                case BOOLEAN:
                case STRING:
                case BYTE_STRING:
                case ENUM:
                    // Use field name which is specified in proto file.
                    builder.put(key, new Field(field, parentNames, field.getJavaType(), isRequired));
                    break;
                case MESSAGE:
                    @Nullable
                    final JavaType wellKnownFieldType = getJavaTypeForWellKnownTypes(field);

                    if (wellKnownFieldType != null) {
                        builder.put(key, new Field(field, parentNames, wellKnownFieldType, isRequired));
                        break;
                    }

                    if (visitedTypes.contains(field.getMessageType())) {
                        // Found recursion. No more analysis for this type.
                        // Raise an exception in order to mark the root parameter as JavaType.MESSAGE.
                        throw new RecursiveTypeException(field.getMessageType());
                    }

                    final Descriptor typeDesc = field.getMessageType();
                    // The json name should be used for the parent name because the keys are used to decode the
                    // JSON to the message by the Protobuf JSON decoder.
                    final String newParentName = field.getJsonName();
                    try {
                        for (HttpJsonTranscodingQueryParamMatchRule nestedMatchRule : matchRules) {
                            builder.putAll(buildFields(typeDesc,
                                                       ImmutableList.<String>builder()
                                                                    .addAll(parentNames)
                                                                    .add(newParentName)
                                                                    .build(),
                                                       key,
                                                       ImmutableSet.<Descriptor>builder()
                                                                   .addAll(visitedTypes)
                                                                   .add(field.getMessageType())
                                                                   .build(),
                                                       nestedMatchRule, matchRules));
                        }
                    } catch (RecursiveTypeException e) {
                        if (e.recursiveTypeDescriptor() != field.getMessageType()) {
                            // Re-throw the exception if it is not caused by my field.
                            throw e;
                        }

                        builder.put(key, new Field(field, parentNames, JavaType.MESSAGE, isRequired));
                    }
                    break;
            }
        }
        // A generated field in LOWER_CAMEL_CASE from a single word such as 'text' could be conflict with the
        // original field name.
        return builder.buildKeepingLast();
    }

    private static boolean hasRequiredFieldBehavior(FieldDescriptor field) {
        if (field.isRepeated()) {
            return false;
        }

        final List<FieldBehavior> fieldBehaviors = field
                .getOptions()
                .getExtension((ExtensionLite<DescriptorProtos.FieldOptions, List<FieldBehavior>>)
                                      FieldBehaviorProto.fieldBehavior);
        return fieldBehaviors.contains(FieldBehavior.REQUIRED);
    }

    @Nullable
    private static JavaType getJavaTypeForWellKnownTypes(FieldDescriptor fd) {
        // MapField can be sent only via HTTP body.
        if (fd.isMapField()) {
            return JavaType.MESSAGE;
        }

        final Descriptor messageType = fd.getMessageType();
        final String fullName = messageType.getFullName();

        if (Timestamp.getDescriptor().getFullName().equals(fullName) ||
            Duration.getDescriptor().getFullName().equals(fullName) ||
            FieldMask.getDescriptor().getFullName().equals(fullName)) {
            return JavaType.STRING;
        }

        if (isScalarValueWrapperMessage(fullName)) {
            // "value" field. Wrappers must have one field.
            assert messageType.getFields().size() == 1 : "Wrappers must have one 'value' field.";
            return messageType.getFields().get(0).getJavaType();
        }

        // The messages of the following types can be sent only via HTTP body.
        if (Struct.getDescriptor().getFullName().equals(fullName) ||
            ListValue.getDescriptor().getFullName().equals(fullName) ||
            Value.getDescriptor().getFullName().equals(fullName) ||
            // google.protobuf.Any message has the following two fields:
            //   string type_url = 1;
            //   bytes value = 2;
            // which look acceptable as HTTP GET parameters, but the client must send the message like below:
            // {
            //   "@type": "type.googleapis.com/google.protobuf.Duration",
            //   "value": "1.212s"
            // }
            // There's no specifications about rewriting parameter names, so we will handle
            // google.protobuf.Any message only when it is sent via HTTP body.
            Any.getDescriptor().getFullName().equals(fullName)) {
            return JavaType.MESSAGE;
        }

        return null;
    }

    private static boolean isScalarValueWrapperMessage(String fullName) {
        return DoubleValue.getDescriptor().getFullName().equals(fullName) ||
               FloatValue.getDescriptor().getFullName().equals(fullName) ||
               Int64Value.getDescriptor().getFullName().equals(fullName) ||
               UInt64Value.getDescriptor().getFullName().equals(fullName) ||
               Int32Value.getDescriptor().getFullName().equals(fullName) ||
               UInt32Value.getDescriptor().getFullName().equals(fullName) ||
               BoolValue.getDescriptor().getFullName().equals(fullName) ||
               StringValue.getDescriptor().getFullName().equals(fullName) ||
               BytesValue.getDescriptor().getFullName().equals(fullName);
    }

    // to make it more efficient, we calculate whether extract response body one time
    // if there is no matching toplevel field, we set it to null
    @Nullable
    private static String getResponseBody(List<FieldDescriptor> topLevelFields,
                                          @Nullable String responseBody) {
        if (StringUtil.isNullOrEmpty(responseBody)) {
            return null;
        }
        for (FieldDescriptor fieldDescriptor : topLevelFields) {
            if (fieldDescriptor.getName().equals(responseBody)) {
                return responseBody;
            }
        }
        return null;
    }

    private static void doRegisterRoute(Map<Route, TranscodingSpec> routeAndSpecs,
                                        Route route,
                                        TranscodingSpec spec) {
        final TranscodingSpec existing = routeAndSpecs.get(route);
        if (existing != null) {
            throw new IllegalStateException(
                    String.format("Duplicate route: %s is mapped to both method '%s' and method '%s'. " +
                                  "Each HTTP route can only be mapped to one gRPC method.",
                                  route,
                                  existing.methodDescriptor().getFullName(),
                                  spec.methodDescriptor().getFullName()));
        }
        routeAndSpecs.put(route, spec);
    }

    GrpcService build() {
        if (httpRules.isEmpty()) {
            return delegate;
        }
        final Map<Route, TranscodingSpec> routeAndSpecs = buildRouteAndSpecs();
        return new HttpJsonTranscodingService(delegate, routeAndSpecs, options);
    }

    private void registerHttpRule(Descriptors.MethodDescriptor method, HttpRule httpRule) {
        final HttpRule oldRule = httpRules.get(method);
        if (oldRule == null) {
            httpRules.put(method, httpRule);
            return;
        }

        final HttpRule resolved = options.conflictStrategy()
                                         .resolve(method, oldRule, httpRule);
        if (resolved != httpRule && resolved != oldRule) {
            throw new IllegalStateException(
                    "HttpRule returned by conflict strategy must be either the existing rule or " +
                    "the new rule, but got a different instance for selector: " + method.getFullName());
        }
        httpRules.put(method, resolved);
    }

    private Map<Route, TranscodingSpec> buildRouteAndSpecs() {
        final Map<Route, TranscodingSpec> routeAndSpecs = new HashMap<>();
        for (Map.Entry<Descriptors.MethodDescriptor, HttpRule> entry : httpRules.entrySet()) {
            final MethodDescriptor desc = entry.getKey();
            final GrpcMethod method = methods.get(desc.getFullName());
            assert method != null;
            registerRoute(routeAndSpecs, method, entry.getValue());
        }
        return ImmutableMap.copyOf(routeAndSpecs);
    }

    private void registerRoute(Map<Route, TranscodingSpec> routeAndSpecs,
                               GrpcMethod method, HttpRule httpRule) {
        final ServerMethodDefinition<?, ?> definition = method.definition;
        if (definition.getMethodDescriptor().getType() != MethodType.UNARY) {
            logger.warn("Only unary methods can be configured with an HTTP/JSON endpoint: " +
                        "method={}, httpRule={}",
                        definition.getMethodDescriptor().getFullMethodName(), httpRule);
            return;
        }

        @Nullable
        final HttpJsonTranscodingRouteAndPathVariables routeAndVariables =
                HttpJsonTranscodingRouteAndPathVariables.of(httpRule);
        if (routeAndVariables == null) {
            return;
        }

        final Descriptors.MethodDescriptor desc = method.descriptor;
        final Set<HttpJsonTranscodingQueryParamMatchRule> queryParamMatchRules = options.queryParamMatchRules();
        final Route route = routeAndVariables.route();
        final Map<String, Field> originalFields = buildFields(desc.getInputType(),
                                                              ImmutableList.of(),
                                                              "",
                                                              ImmutableSet.of(),
                                                              ORIGINAL_FIELD,
                                                              ImmutableSet.of(ORIGINAL_FIELD));
        final List<Map<String, Field>> queryMappingFields =
                queryParamMatchRules.stream().map(matchRule -> {
                    return buildFields(desc.getInputType(),
                                       ImmutableList.of(),
                                       "",
                                       ImmutableSet.of(),
                                       matchRule, queryParamMatchRules);
                }).collect(toImmutableList());
        final List<FieldDescriptor> topLevelFields = desc.getOutputType().getFields();
        final String responseBody = getResponseBody(topLevelFields, httpRule.getResponseBody());

        final TranscodingSpec newSpec = new TranscodingSpec(
                0, httpRule, definition, desc.getService(), desc, originalFields, queryMappingFields,
                routeAndVariables.pathVariables(), routeAndVariables.hasVerb(), responseBody);
        doRegisterRoute(routeAndSpecs, route, newSpec);

        int order = 1;
        for (HttpRule additionalHttpRule : httpRule.getAdditionalBindingsList()) {
            final HttpJsonTranscodingRouteAndPathVariables additionalRouteAndVariables =
                    HttpJsonTranscodingRouteAndPathVariables.of(additionalHttpRule);
            if (additionalRouteAndVariables != null) {
                final TranscodingSpec additionalSpec = new TranscodingSpec(
                        order++, additionalHttpRule, definition, desc.getService(), desc, originalFields,
                        queryMappingFields, additionalRouteAndVariables.pathVariables(),
                        additionalRouteAndVariables.hasVerb(), responseBody);
                doRegisterRoute(routeAndSpecs, additionalRouteAndVariables.route(), additionalSpec);
            }
        }
    }

    private static final class GrpcMethod {

        final ServerMethodDefinition<?, ?> definition;
        final Descriptors.MethodDescriptor descriptor;

        GrpcMethod(ServerMethodDefinition<?, ?> definition, Descriptors.MethodDescriptor descriptor) {
            this.definition = definition;
            this.descriptor = descriptor;
        }
    }

    /**
     * Notifies that a recursively nesting type exists.
     */
    private static class RecursiveTypeException extends IllegalArgumentException {
        private static final long serialVersionUID = -6764357154559606786L;

        private final Descriptor recursiveTypeDescriptor;

        RecursiveTypeException(Descriptor recursiveTypeDescriptor) {
            this.recursiveTypeDescriptor = recursiveTypeDescriptor;
        }

        Descriptor recursiveTypeDescriptor() {
            return recursiveTypeDescriptor;
        }

        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }
}
