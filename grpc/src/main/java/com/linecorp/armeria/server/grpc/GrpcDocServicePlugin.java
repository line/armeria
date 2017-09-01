/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.docs.DocServicePlugin;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.EnumInfo;
import com.linecorp.armeria.server.docs.EnumValueInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.NamedTypeInfo;
import com.linecorp.armeria.server.docs.ServiceInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.StructInfo;
import com.linecorp.armeria.server.docs.TypeSignature;

import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoFileDescriptorSupplier;

/**
 * {@link DocServicePlugin} implementation that supports {@link GrpcService}s.
 */
public class GrpcDocServicePlugin implements DocServicePlugin {

    @VisibleForTesting
    static final TypeSignature BOOL = TypeSignature.ofBase("bool");
    @VisibleForTesting
    static final TypeSignature INT32 = TypeSignature.ofBase("int32");
    @VisibleForTesting
    static final TypeSignature INT64 = TypeSignature.ofBase("int64");
    @VisibleForTesting
    static final TypeSignature UINT32 = TypeSignature.ofBase("uint32");
    @VisibleForTesting
    static final TypeSignature UINT64 = TypeSignature.ofBase("uint64");
    @VisibleForTesting
    static final TypeSignature SINT32 = TypeSignature.ofBase("sint32");
    @VisibleForTesting
    static final TypeSignature SINT64 = TypeSignature.ofBase("sint64");
    @VisibleForTesting
    static final TypeSignature FLOAT = TypeSignature.ofBase("float");
    @VisibleForTesting
    static final TypeSignature DOUBLE = TypeSignature.ofBase("double");
    @VisibleForTesting
    static final TypeSignature FIXED32 = TypeSignature.ofBase("fixed32");
    @VisibleForTesting
    static final TypeSignature FIXED64 = TypeSignature.ofBase("fixed64");
    @VisibleForTesting
    static final TypeSignature SFIXED32 = TypeSignature.ofBase("sfixed32");
    @VisibleForTesting
    static final TypeSignature SFIXED64 = TypeSignature.ofBase("sfixed64");
    @VisibleForTesting
    static final TypeSignature STRING = TypeSignature.ofBase("string");
    @VisibleForTesting
    static final TypeSignature BYTES = TypeSignature.ofBase("bytes");
    @VisibleForTesting
    static final TypeSignature UNKNOWN = TypeSignature.ofBase("unknown");

    private final GrpcDocStringExtractor docstringExtractor = new GrpcDocStringExtractor();

    @Override
    public Set<Class<? extends Service<?, ?>>> supportedServiceTypes() {
        return ImmutableSet.of(GrpcService.class);
    }

    @Override
    public ServiceSpecification generateSpecification(Set<ServiceConfig> serviceConfigs) {
        final Map<String, ServiceEntryBuilder> map = new LinkedHashMap<>();
        for (ServiceConfig serviceConfig : serviceConfigs) {
            final GrpcService grpcService = serviceConfig.service().as(GrpcService.class).get();
            ImmutableSet.Builder<MediaType> supportedMediaTypesBuilder = ImmutableSet.builder();
            supportedMediaTypesBuilder.addAll(grpcService.supportedSerializationFormats()
                                                         .stream()
                                                         .map(SerializationFormat::mediaType)::iterator);
            if (serviceConfig.service().as(UnframedGrpcService.class).isPresent()) {
                if (grpcService.supportedSerializationFormats().contains(GrpcSerializationFormats.PROTO)) {
                    // Normal clients of a GrpcService are not required to set a protocol when using unframed
                    // requests but we set it here for clarity in DocService, where there may be multiple
                    // services with similar mime types but different protocols.
                    supportedMediaTypesBuilder.add(MediaType.PROTOBUF.withParameter("protocol", "gRPC"));
                }
                if (grpcService.supportedSerializationFormats().contains(GrpcSerializationFormats.JSON)) {
                    supportedMediaTypesBuilder.add(MediaType.JSON_UTF_8.withParameter("protocol", "gRPC"));
                }
            }
            Set<MediaType> supportedMediaTypes = supportedMediaTypesBuilder.build();
            for (ServerServiceDefinition service : grpcService.services()) {
                map.computeIfAbsent(
                        service.getServiceDescriptor().getName(),
                        s -> {
                            FileDescriptor fileDescriptor =
                                    ((ProtoFileDescriptorSupplier)
                                            service.getServiceDescriptor().getSchemaDescriptor())
                                            .getFileDescriptor();
                            ServiceDescriptor serviceDescriptor = fileDescriptor.getServices().stream()
                                          .filter(sd -> sd.getFullName().equals(
                                                  service.getServiceDescriptor().getName()))
                                          .findFirst()
                                          .orElseThrow(IllegalStateException::new);
                            return new ServiceEntryBuilder(serviceDescriptor);
                        });
            }
            serviceConfig.pathMapping().prefix().ifPresent(
                    path -> {
                        for (ServerServiceDefinition service : grpcService.services()) {
                            final String serviceName = service.getServiceDescriptor().getName();
                            map.get(serviceName).endpoint(
                                    new EndpointInfo(
                                            serviceConfig.virtualHost().hostnamePattern(),
                                            // Only the URL prefix, each method is served at a different path.
                                            path + serviceName + '/',
                                            "",
                                            // No default mime type for gRPC, so just pick arbitrarily for now.
                                            // TODO(anuraag): Consider allowing default mime type to be null.
                                            Iterables.getFirst(supportedMediaTypes,
                                                               GrpcSerializationFormats.PROTO.mediaType()),
                                            supportedMediaTypes));
                        }
                    });
        }
        return generate(map.values().stream()
                           .map(ServiceEntryBuilder::build)
                           .collect(toImmutableList()));
    }

    @Override
    public Map<String, String> loadDocStrings(Set<ServiceConfig> serviceConfigs) {
        return serviceConfigs.stream()
                .flatMap(c -> c.service().as(GrpcService.class).get().services().stream())
                .flatMap(s -> docstringExtractor.getAllDocStrings(s.getClass().getClassLoader())
                                                .entrySet().stream())
                .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
    }

    @Override
    public Set<Class<?>> supportedExampleRequestTypes() {
        return ImmutableSet.of(Message.class);
    }

    @Override
    public Optional<String> serializeExampleRequest(String serviceName, String methodName,
                                                    Object exampleRequest) {
        try {
            return Optional.of(JsonFormat.printer().print((Message) exampleRequest));
        } catch (InvalidProtocolBufferException e) {
            throw new UncheckedIOException(
                    "Invalid example request protobuf. Is it missing required fields?", e);
        }
    }

    @VisibleForTesting
    ServiceSpecification generate(List<ServiceEntry> entries) {
        final List<ServiceInfo> services = entries.stream()
                                                  .map(this::newServiceInfo)
                                                  .collect(toImmutableList());
        return ServiceSpecification.generate(services, this::newNamedTypeInfo);
    }

    private NamedTypeInfo newNamedTypeInfo(TypeSignature typeSignature) {
        Object descriptor = typeSignature.namedTypeDescriptor().get();
        if (descriptor instanceof Descriptor) {
            return newStructInfo((Descriptor) descriptor);
        }

        assert descriptor instanceof EnumDescriptor;
        return newEnumInfo((EnumDescriptor) descriptor);
    }

    ServiceInfo newServiceInfo(ServiceEntry entry) {
        final List<MethodInfo> functions = entry.methods().stream()
                                                .map(m -> newMethodInfo(m, entry))
                                                .collect(toImmutableList());
        return new ServiceInfo(entry.name(), functions);
    }

    @VisibleForTesting
    MethodInfo newMethodInfo(MethodDescriptor method, ServiceEntry service) {
        Set<EndpointInfo> methodEndpoints =
                service.endpointInfos.stream()
                                     .map(e -> new EndpointInfo(
                                             e.hostnamePattern(),
                                             e.path() + method.getName(),
                                             e.fragment(),
                                             e.defaultMimeType(),
                                             e.availableMimeTypes()))
                                     .collect(toImmutableSet());
        return new MethodInfo(
                method.getName(),
                namedMessageSignature(method.getOutputType()),
                // gRPC methods always take a single request parameter of message type.
                ImmutableList.of(
                        new FieldInfo(
                                "request",
                                FieldRequirement.REQUIRED,
                                namedMessageSignature(method.getInputType()))),
                ImmutableList.of(),
                methodEndpoints);
    }

    @VisibleForTesting
    StructInfo newStructInfo(Descriptor descriptor) {
        return new StructInfo(
                descriptor.getFullName(),
                descriptor.getFields().stream()
                          .map(this::newFieldInfo)
                          .collect(toImmutableList()));
    }

    private FieldInfo newFieldInfo(FieldDescriptor fieldDescriptor) {
        return new FieldInfo(
                fieldDescriptor.getName(),
                fieldDescriptor.isRequired() ? FieldRequirement.REQUIRED : FieldRequirement.OPTIONAL,
                newFieldTypeInfo(fieldDescriptor));
    }

    @VisibleForTesting
    TypeSignature newFieldTypeInfo(FieldDescriptor fieldDescriptor) {
        if (fieldDescriptor.isMapField()) {
            return TypeSignature.ofMap(
                    newFieldTypeInfo(fieldDescriptor.getMessageType().findFieldByNumber(1)),
                    newFieldTypeInfo(fieldDescriptor.getMessageType().findFieldByNumber(2)));
        }
        final TypeSignature fieldType;
        switch (fieldDescriptor.getType()) {
            case BOOL:
                fieldType = BOOL;
                break;
            case BYTES:
                fieldType = BYTES;
                break;
            case DOUBLE:
                fieldType = DOUBLE;
                break;
            case FIXED32:
                fieldType = FIXED32;
                break;
            case FIXED64:
                fieldType = FIXED64;
                break;
            case FLOAT:
                fieldType = FLOAT;
                break;
            case INT32:
                fieldType = INT32;
                break;
            case INT64:
                fieldType = INT64;
                break;
            case SFIXED32:
                fieldType = SFIXED32;
                break;
            case SFIXED64:
                fieldType = SFIXED64;
                break;
            case SINT32:
                fieldType = SINT32;
                break;
            case SINT64:
                fieldType = SINT64;
                break;
            case STRING:
                fieldType = STRING;
                break;
            case UINT32:
                fieldType = UINT32;
                break;
            case UINT64:
                fieldType = UINT64;
                break;
            case MESSAGE:
                fieldType = namedMessageSignature(fieldDescriptor.getMessageType());
                break;
            case GROUP:
                // This type has been deprecated since the launch of protocol buffers to open source.
                // There is no real metadata for this in the descriptor so we just treat as UNKNOWN
                // since it shouldn't happen in practice anyways.
                fieldType = UNKNOWN;
                break;
            case ENUM:
                fieldType = TypeSignature.ofNamed(
                        fieldDescriptor.getEnumType().getFullName(), fieldDescriptor.getEnumType());
                break;
            default:
                fieldType = UNKNOWN;
                break;
        }
        return fieldDescriptor.isRepeated() ? TypeSignature.ofContainer("repeated", fieldType) : fieldType;
    }

    @VisibleForTesting
    EnumInfo newEnumInfo(EnumDescriptor enumDescriptor) {
        return new EnumInfo(
                enumDescriptor.getFullName(),
                enumDescriptor.getValues().stream()
                              .map(d -> new EnumValueInfo(d.getName()))
                              .collect(toImmutableList()));
    }

    private TypeSignature namedMessageSignature(Descriptor descriptor) {
        return TypeSignature.ofNamed(descriptor.getFullName(), descriptor);
    }

    @VisibleForTesting
    static final class ServiceEntry {
        final ServiceDescriptor service;
        final List<EndpointInfo> endpointInfos;

        ServiceEntry(ServiceDescriptor service,
                     List<EndpointInfo> endpointInfos) {
            this.service = service;
            this.endpointInfos = ImmutableList.copyOf(endpointInfos);
        }

        String name() {
            return service.getFullName();
        }

        List<MethodDescriptor> methods() {
            return ImmutableList.copyOf(service.getMethods());
        }
    }

    @VisibleForTesting
    static final class ServiceEntryBuilder {
        private final ServiceDescriptor service;
        private final List<EndpointInfo> endpointInfos = new ArrayList<>();

        ServiceEntryBuilder(ServiceDescriptor service) {
            this.service = service;
        }

        ServiceEntryBuilder endpoint(EndpointInfo endpointInfo) {
            endpointInfos.add(requireNonNull(endpointInfo, "endpointInfo"));
            return this;
        }

        ServiceEntry build() {
            return new ServiceEntry(service, endpointInfos);
        }
    }
}
