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

package com.linecorp.armeria.internal.server.grpc;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.concatPaths;
import static com.linecorp.armeria.internal.server.RouteUtil.innermostRoute;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin.endpointInfoBuilder;
import static com.linecorp.armeria.internal.server.grpc.GrpcMethodUtil.extractMethodName;
import static java.util.Objects.requireNonNull;

import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.internal.server.grpc.HttpEndpointSpecification.Parameter;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RoutePathType;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.docs.DocServicePlugin;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.EnumInfo;
import com.linecorp.armeria.server.docs.EnumValueInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldInfoBuilder;
import com.linecorp.armeria.server.docs.FieldLocation;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.NamedTypeInfo;
import com.linecorp.armeria.server.docs.ServiceInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.StructInfo;
import com.linecorp.armeria.server.docs.TypeSignature;
import com.linecorp.armeria.server.grpc.GrpcService;

import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoFileDescriptorSupplier;

/**
 * {@link DocServicePlugin} implementation that supports {@link GrpcService}s.
 */
public final class GrpcDocServicePlugin implements DocServicePlugin {

    private static final Logger logger = LoggerFactory.getLogger(GrpcDocServicePlugin.class);

    private static final String NAME = "grpc";

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

    @VisibleForTesting
    public static final String HTTP_SERVICE_SUFFIX = "_HTTP";

    private static final JsonFormat.Printer defaultExamplePrinter =
            JsonFormat.printer().includingDefaultValueFields();

    private final GrpcDocStringExtractor docstringExtractor = new GrpcDocStringExtractor();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<Class<? extends Service<?, ?>>> supportedServiceTypes() {
        return ImmutableSet.of(GrpcService.class);
    }

    @Override
    public ServiceSpecification generateSpecification(Set<ServiceConfig> serviceConfigs,
                                                      DocServiceFilter filter) {
        requireNonNull(serviceConfigs, "serviceConfigs");
        requireNonNull(filter, "filter");

        final Set<GrpcService> addedService = new HashSet<>();
        final ImmutableList.Builder<HttpEndpoint> httpEndpoints = ImmutableList.builder();
        final ServiceInfosBuilder serviceInfosBuilder = new ServiceInfosBuilder();
        for (ServiceConfig serviceConfig : serviceConfigs) {
            final GrpcService grpcService = serviceConfig.service().as(GrpcService.class);
            assert grpcService != null;

            if (addedService.add(grpcService)) {
                addServiceDescriptor(serviceInfosBuilder, grpcService);
            }

            final Route route = serviceConfig.route();
            if (grpcService instanceof HttpEndpointSupport) {
                // grpcService is a HttpJsonTranscodingService.
                final HttpEndpointSpecification spec =
                        ((HttpEndpointSupport) grpcService).httpEndpointSpecification(innermostRoute(route));
                if (spec != null) {
                    if (filter.test(NAME, spec.serviceName(), spec.methodName())) {
                        httpEndpoints.add(new HttpEndpoint(serviceConfig, spec.withRoute(route)));
                    }
                    continue;
                } else {
                    // The current route is one of the routes from FramedGrpcService.routes()
                    // so we add it below.
                }
            }

            final Set<MediaType> supportedMediaTypes = supportedMediaTypes(grpcService);
            if (route.pathType() == RoutePathType.PREFIX) {
                // The route is PREFIX type when the grpcService is set via route builder:
                // - serverBuilder.route().pathPrefix("/prefix")...build(grpcService);
                // So we add endpoints of all methods in the grpcService with the pathPrefix.
                final String pathPrefix = route.paths().get(0);
                grpcService.methodsByRoute().forEach((methodRoute, method) -> {
                    final EndpointInfo endpointInfo =
                            EndpointInfo.builder(serviceConfig.virtualHost().hostnamePattern(),
                                                 concatPaths(pathPrefix, methodRoute.patternString()))
                                        .availableMimeTypes(supportedMediaTypes)
                                        .build();

                    serviceInfosBuilder.addEndpoint(method.getMethodDescriptor(), endpointInfo);
                });
            } else if (route.pathType() == RoutePathType.EXACT) {
                // The route is EXACT type when the grpcService is set via:
                // - serverBuilder.service(grpcService);
                // - serverBuilder.serviceUnder("/prefix", grpcService);
                final ServerMethodDefinition<?, ?> methodDefinition =
                        grpcService.methodsByRoute().get(innermostRoute(route));
                assert methodDefinition != null;
                final EndpointInfo endpointInfo =
                        EndpointInfo.builder(serviceConfig.virtualHost().hostnamePattern(),
                                             route.patternString())
                                    .availableMimeTypes(supportedMediaTypes)
                                    .build();
                serviceInfosBuilder.addEndpoint(methodDefinition.getMethodDescriptor(), endpointInfo);
            } else {
                // Should never reach here.
                throw new Error();
            }
        }

        return generate(ImmutableList.<ServiceInfo>builder()
                                     .addAll(serviceInfosBuilder.build(filter))
                                     .addAll(buildHttpServiceInfos(httpEndpoints.build()))
                                     .build());
    }

    private static void addServiceDescriptor(ServiceInfosBuilder serviceInfosBuilder, GrpcService grpcService) {
        grpcService.services().stream()
                   .map(ServerServiceDefinition::getServiceDescriptor)
                   .filter(Objects::nonNull)
                   .filter(desc -> desc.getSchemaDescriptor() instanceof ProtoFileDescriptorSupplier)
                   .forEach(desc -> {
                       final String serviceName = desc.getName();
                       final ProtoFileDescriptorSupplier fileDescSupplier =
                               (ProtoFileDescriptorSupplier) desc.getSchemaDescriptor();
                       final FileDescriptor fileDesc = fileDescSupplier.getFileDescriptor();
                       final ServiceDescriptor serviceDesc =
                               fileDesc.getServices().stream()
                                       .filter(sd -> sd.getFullName().equals(serviceName))
                                       .findFirst()
                                       .orElseThrow(IllegalStateException::new);
                       serviceInfosBuilder.addService(serviceDesc);
                   });
    }

    private static Set<MediaType> supportedMediaTypes(GrpcService grpcService) {
        final ImmutableSet.Builder<MediaType> supportedMediaTypesBuilder = ImmutableSet.builder();
        supportedMediaTypesBuilder.addAll(grpcService.supportedSerializationFormats()
                                                     .stream()
                                                     .map(SerializationFormat::mediaType)::iterator);

        if (!grpcService.isFramed()) {
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
        return supportedMediaTypesBuilder.build();
    }

    @VisibleForTesting
    static List<ServiceInfo> buildHttpServiceInfos(List<HttpEndpoint> httpEndpoints) {
        if (httpEndpoints.isEmpty()) {
            return ImmutableList.of();
        }

        final Multimap<String, HttpEndpoint> byServiceName = HashMultimap.create();
        httpEndpoints.forEach(
                httpEndpoint -> byServiceName.put(httpEndpoint.spec().serviceName(), httpEndpoint));

        final ImmutableList.Builder<ServiceInfo> serviceInfos = ImmutableList.builder();
        byServiceName.asMap().forEach(
                (key, value) -> serviceInfos.add(buildHttpServiceInfo(key + HTTP_SERVICE_SUFFIX, value)));
        return serviceInfos.build();
    }

    private static ServiceInfo buildHttpServiceInfo(String serviceName,
                                                    Collection<HttpEndpoint> endpoints) {
        final Multimap<String, HttpEndpoint> byMethodName = HashMultimap.create();
        endpoints.stream()
                 // Order by gRPC method name and the specified order of HTTP endpoints.
                 .sorted(Comparator.comparing(
                         httpEndpoint -> httpEndpoint.spec().methodName() + httpEndpoint.spec().order()))
                 // Group by gRPC method name and HTTP method.
                 .forEach(entry -> byMethodName.put(entry.spec().methodName() + '/' + entry.httpMethod(),
                                                    entry));

        final ImmutableList.Builder<MethodInfo> methodInfos = ImmutableList.builder();
        byMethodName.asMap().forEach((name, httpEndpoints) -> {
            final List<HttpEndpoint> sortedEndpoints =
                    httpEndpoints.stream().sorted(Comparator.comparingInt(ep -> ep.spec.order()))
                                 .collect(toImmutableList());

            final HttpEndpoint firstEndpoint = sortedEndpoints.get(0);
            final HttpEndpointSpecification firstSpec = firstEndpoint.spec();

            final ImmutableList.Builder<FieldInfo> fieldInfosBuilder = ImmutableList.builder();
            firstSpec.pathVariables().forEach(paramName -> {
                @Nullable
                final Parameter parameter = firstSpec.parameters().get(paramName);
                // It is possible not to have a gRPC field mapped by a path variable.
                // In this case, we treat the type of the path variable as 'String'.
                final TypeSignature typeSignature =
                        parameter != null ? toTypeSignature(parameter)
                                          : TypeSignature.ofBase(JavaType.STRING.name());
                fieldInfosBuilder.add(FieldInfo.builder(paramName, typeSignature)
                                               .requirement(FieldRequirement.REQUIRED)
                                               .location(FieldLocation.PATH)
                                               .build());
            });
            final String bodyParamName = firstSpec.httpRule().getBody();
            final FieldLocation fieldLocation = Strings.isNullOrEmpty(bodyParamName) ? FieldLocation.QUERY
                                                                                     : FieldLocation.BODY;
            firstSpec.parameters().forEach((paramName, parameter) -> {
                if (!firstSpec.pathVariables().contains(paramName)) {
                    final FieldInfoBuilder builder;
                    if (fieldLocation == FieldLocation.BODY && !"*".equals(bodyParamName) &&
                        paramName.startsWith(bodyParamName + '.')) {
                        builder = FieldInfo.builder(paramName.substring(bodyParamName.length() + 1),
                                                    toTypeSignature(parameter));
                    } else {
                        builder = FieldInfo.builder(paramName, toTypeSignature(parameter));
                    }

                    fieldInfosBuilder.add(builder.requirement(FieldRequirement.REQUIRED)
                                                 .location(fieldLocation)
                                                 .build());
                }
            });

            final List<EndpointInfo> endpointInfos =
                    sortedEndpoints.stream()
                                   .map(httpEndpoint -> endpointInfoBuilder(
                                           httpEndpoint.spec().route(),
                                           httpEndpoint.config().virtualHost().hostnamePattern())
                                           // DocService client works only if the media type equals to
                                           // 'application/json; charset=utf-8'.
                                           .availableMimeTypes(MediaType.JSON_UTF_8)
                                           .build()).collect(toImmutableList());

            final List<String> examplePaths =
                    sortedEndpoints.stream().map(httpEndpoint -> httpEndpoint.spec().route().patternString())
                                   .collect(toImmutableList());

            final List<String> exampleQueries =
                    sortedEndpoints.stream().map(httpEndpoint -> {
                        final HttpEndpointSpecification spec = httpEndpoint.spec();
                        return spec.parameters().entrySet().stream()
                                   // Exclude path parameters.
                                   .filter(entry -> !spec.pathVariables().contains(entry.getKey()))
                                   // Join all remaining parameters as a single query string.
                                   .map(p -> p.getKey() + '=' + p.getValue().type().name())
                                   .collect(Collectors.joining("&"));
                    }).filter(queries -> !queries.isEmpty()).collect(toImmutableList());

            methodInfos.add(new MethodInfo(
                    // Order 0 is primary.
                    firstSpec.order() == 0 ? firstSpec.methodName()
                                           : firstSpec.methodName() + '-' + firstSpec.order(),
                    namedMessageSignature(firstSpec.methodDescriptor().getOutputType()),
                    fieldInfosBuilder.build(),
                    /* exceptionTypeSignatures */ ImmutableList.of(),
                    endpointInfos,
                    /* exampleHeaders */ ImmutableList.of(),
                    /* exampleRequests */ ImmutableList.of(),
                    examplePaths,
                    exampleQueries,
                    firstEndpoint.httpMethod(),
                    /* docString */ null));
        });
        return new ServiceInfo(serviceName, methodInfos.build());
    }

    private static TypeSignature toTypeSignature(Parameter parameter) {
        final TypeSignature typeSignature = TypeSignature.ofBase(parameter.type().name());
        return parameter.isRepeated() ? TypeSignature.ofList(typeSignature) : typeSignature;
    }

    @Override
    public Map<String, String> loadDocStrings(Set<ServiceConfig> serviceConfigs) {
        return serviceConfigs.stream()
                             .flatMap(c -> {
                                 final GrpcService grpcService = c.service().as(GrpcService.class);
                                 assert grpcService != null;
                                 return grpcService.services().stream();
                             })
                             .flatMap(s -> docstringExtractor.getAllDocStrings(s.getClass().getClassLoader())
                                                             .entrySet().stream())
                             .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
    }

    @Override
    public Set<Class<?>> supportedExampleRequestTypes() {
        return ImmutableSet.of(MessageOrBuilder.class);
    }

    @Override
    public String serializeExampleRequest(String serviceName, String methodName,
                                          Object exampleRequest) {
        try {
            return defaultExamplePrinter.print((MessageOrBuilder) exampleRequest);
        } catch (InvalidProtocolBufferException e) {
            throw new UncheckedIOException(
                    "Invalid example request protobuf. Is it missing required fields?", e);
        }
    }

    @VisibleForTesting
    ServiceSpecification generate(List<ServiceInfo> services) {
        return ServiceSpecification.generate(services, this::newNamedTypeInfo);
    }

    private NamedTypeInfo newNamedTypeInfo(TypeSignature typeSignature) {
        final Object descriptor = typeSignature.namedTypeDescriptor();
        if (descriptor instanceof Descriptor) {
            return newStructInfo((Descriptor) descriptor);
        }

        assert descriptor instanceof EnumDescriptor;
        return newEnumInfo((EnumDescriptor) descriptor);
    }

    @VisibleForTesting
    static MethodInfo newMethodInfo(MethodDescriptor method, Set<EndpointInfo> endpointInfos) {
        return new MethodInfo(
                method.getName(),
                namedMessageSignature(method.getOutputType()),
                // gRPC methods always take a single request parameter of message type.
                ImmutableList.of(FieldInfo.builder("request", namedMessageSignature(method.getInputType()))
                                          .requirement(FieldRequirement.REQUIRED).build()),
                /* exceptionTypeSignatures */ ImmutableList.of(),
                endpointInfos,
                /* exampleHeaders */ ImmutableList.of(),
                defaultExamples(method),
                /* examplePaths */ ImmutableList.of(),
                /* exampleQueries */ ImmutableList.of(),
                HttpMethod.POST,
                /* docString */ null);
    }

    private static List<String> defaultExamples(MethodDescriptor method) {
        try {
            final DynamicMessage defaultInput = DynamicMessage.getDefaultInstance(method.getInputType());
            final String serialized = defaultExamplePrinter.print(defaultInput).trim();
            if ("{\n}".equals(serialized) || "{}".equals(serialized)) {
                // Ignore an empty object.
                return ImmutableList.of();
            }
            return ImmutableList.of(serialized);
        } catch (InvalidProtocolBufferException e) {
            return ImmutableList.of();
        }
    }

    @VisibleForTesting
    StructInfo newStructInfo(Descriptor descriptor) {
        return new StructInfo(
                descriptor.getFullName(),
                descriptor.getFields().stream()
                          .map(GrpcDocServicePlugin::newFieldInfo)
                          .collect(toImmutableList()));
    }

    private static FieldInfo newFieldInfo(FieldDescriptor fieldDescriptor) {
        return FieldInfo.builder(fieldDescriptor.getName(), newFieldTypeInfo(fieldDescriptor))
                        .requirement(fieldDescriptor.isRequired() ? FieldRequirement.REQUIRED
                                                                  : FieldRequirement.OPTIONAL)
                        .build();
    }

    @VisibleForTesting
    static TypeSignature newFieldTypeInfo(FieldDescriptor fieldDescriptor) {
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
                              .map(d -> new EnumValueInfo(d.getName(), d.getNumber()))
                              .collect(toImmutableList()));
    }

    private static TypeSignature namedMessageSignature(Descriptor descriptor) {
        return TypeSignature.ofNamed(descriptor.getFullName(), descriptor);
    }

    @Override
    public String toString() {
        return GrpcDocServicePlugin.class.getSimpleName();
    }

    @VisibleForTesting
    static final class HttpEndpoint {
        private final ServiceConfig config;
        private final HttpEndpointSpecification spec;

        HttpEndpoint(ServiceConfig config, HttpEndpointSpecification spec) {
            this.config = config;
            this.spec = spec;
        }

        ServiceConfig config() {
            return config;
        }

        HttpEndpointSpecification spec() {
            return spec;
        }

        HttpMethod httpMethod() {
            // Only one HTTP method can be specified for the route specified by gRPC transcoding.
            return spec.route().methods().stream().findFirst().get();
        }
    }

    @VisibleForTesting
    static final class ServiceInfosBuilder {
        private final Map<String, ServiceDescriptor> services = new LinkedHashMap<>();
        private final Multimap<ServiceDescriptor, MethodDescriptor> methods = HashMultimap.create();
        private final Multimap<MethodDescriptor, EndpointInfo> endpoints = HashMultimap.create();

        ServiceInfosBuilder addService(ServiceDescriptor service) {
            services.put(service.getFullName(), service);
            return this;
        }

        ServiceInfosBuilder addEndpoint(io.grpc.MethodDescriptor<?, ?> grpcMethod, EndpointInfo endpointInfo) {
            final ServiceDescriptor service = services.get(grpcMethod.getServiceName());
            assert service != null;

            final MethodDescriptor method =
                    service.findMethodByName(extractMethodName(grpcMethod.getFullMethodName()));
            assert method != null;

            methods.put(service, method);
            endpoints.put(method, endpointInfo);
            return this;
        }

        List<ServiceInfo> build(DocServiceFilter filter) {
            return methods
                    .asMap().entrySet().stream()
                    .map(entry -> {
                        final String serviceName = entry.getKey().getFullName();
                        final List<MethodInfo> methodInfos =
                                entry.getValue().stream()
                                     .filter(m -> filter.test(NAME, serviceName, m.getName()))
                                     .map(method -> newMethodInfo(method,
                                                                  ImmutableSet.copyOf(endpoints.get(method))))
                                     .collect(toImmutableList());
                        if (methodInfos.isEmpty()) {
                            return null;
                        } else {
                            return new ServiceInfo(serviceName, methodInfos);
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(toImmutableList());
        }
    }
}
