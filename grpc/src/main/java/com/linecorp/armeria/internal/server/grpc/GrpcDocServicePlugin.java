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
import static com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin.endpointInfoBuilder;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
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
import com.linecorp.armeria.server.docs.DescriptionInfo;
import com.linecorp.armeria.server.docs.DescriptiveTypeInfo;
import com.linecorp.armeria.server.docs.DescriptiveTypeInfoProvider;
import com.linecorp.armeria.server.docs.DescriptiveTypeSignature;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.docs.DocServicePlugin;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldInfoBuilder;
import com.linecorp.armeria.server.docs.FieldLocation;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.ServiceInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.TypeSignature;
import com.linecorp.armeria.server.grpc.GrpcService;

import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoFileDescriptorSupplier;

/**
 * {@link DocServicePlugin} implementation that supports {@link GrpcService}s.
 */
public final class GrpcDocServicePlugin implements DocServicePlugin {

    private static final String NAME = "grpc";

    @VisibleForTesting
    public static final String HTTP_SERVICE_SUFFIX = "_HTTP";

    private static final JsonFormat.Printer defaultExamplePrinter =
            JsonFormat.printer().includingDefaultValueFields();

    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\(\\?<([\\w]+)>[^)]+\\)");

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
                                                      DocServiceFilter filter,
                                                      DescriptiveTypeInfoProvider descriptiveTypeInfoProvider) {
        requireNonNull(serviceConfigs, "serviceConfigs");
        requireNonNull(filter, "filter");
        requireNonNull(descriptiveTypeInfoProvider, "descriptiveTypeInfoProvider");

        final Set<GrpcService> addedService = new HashSet<>();
        final ImmutableList.Builder<HttpEndpoint> httpEndpoints = ImmutableList.builder();
        final ServiceInfosBuilder serviceInfosBuilder = new ServiceInfosBuilder();
        for (ServiceConfig serviceConfig : serviceConfigs) {
            final GrpcService grpcService = serviceConfig.service().as(GrpcService.class);
            assert grpcService != null;

            if (addedService.add(grpcService)) {
                addServiceDescriptor(serviceInfosBuilder, grpcService);
            }

            final HttpEndpointSupport httpEndpointSupport = grpcService.as(HttpEndpointSupport.class);
            if (httpEndpointSupport != null) {
                // grpcService can be unwrapped into HttpJsonTranscodingService.
                // There are two routes for a method in HttpJsonTranscodingService:
                // - The HTTP route is added below using the spec.
                // - The auto generated route(e.g. /package.name/MethodName) is added using EndpointInfo.
                final HttpEndpointSpecification spec =
                        httpEndpointSupport.httpEndpointSpecification(
                                serviceConfig.mappedRoute()); // Use mappedRoute to find the specification.
                if (spec != null) {
                    if (filter.test(NAME, spec.serviceName(), spec.methodName())) {
                        httpEndpoints.add(new HttpEndpoint(serviceConfig,
                                                           // Use route which has the full path.
                                                           spec.withRoute(serviceConfig.route())));
                    }
                    continue;
                } else {
                    // The current route is one of the routes from FramedGrpcService.routes()
                    // so we add it below.
                }
            }

            final Route route = serviceConfig.route();
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
                        // Use mappedRoute to find the methodDefinition.
                        grpcService.methodsByRoute().get(serviceConfig.mappedRoute());
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
                                     .build(), descriptiveTypeInfoProvider);
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
                    sortedEndpoints.stream()
                                   .map(httpEndpoint -> {
                                       final Route route = httpEndpoint.spec().route();
                                       if (route.pathType() == RoutePathType.REGEX ||
                                           route.pathType() == RoutePathType.REGEX_WITH_PREFIX) {
                                           return convertRegexPath(route.patternString());
                                       }
                                       return route.patternString();
                                   })
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
                    serviceName,
                    firstSpec.methodName(),
                    firstSpec.order(),
                    descriptiveMessageSignature(firstSpec.methodDescriptor().getOutputType()),
                    fieldInfosBuilder.build(),
                    endpointInfos,
                    examplePaths,
                    exampleQueries,
                    firstEndpoint.httpMethod(), DescriptionInfo.empty()));
        });
        return new ServiceInfo(serviceName, methodInfos.build());
    }

    @VisibleForTesting
    static String convertRegexPath(String patternString) {
        // map '/a/(?<p0>[^/]+):get' to '/a/p0:get'
        return PATH_PARAM_PATTERN.matcher(patternString).replaceAll("$1");
    }

    private static TypeSignature descriptiveMessageSignature(Descriptor descriptor) {
        return TypeSignature.ofStruct(descriptor.getFullName(), descriptor);
    }

    private static TypeSignature toTypeSignature(Parameter parameter) {
        final TypeSignature typeSignature = TypeSignature.ofBase(parameter.type().name());
        return parameter.isRepeated() ? TypeSignature.ofList(typeSignature) : typeSignature;
    }

    @Override
    public Map<String, DescriptionInfo> loadDocStrings(Set<ServiceConfig> serviceConfigs) {
        return serviceConfigs.stream()
                             .flatMap(c -> {
                                 final GrpcService grpcService = c.service().as(GrpcService.class);
                                 assert grpcService != null;
                                 return grpcService.services().stream();
                             })
                             .flatMap(s -> docstringExtractor.getAllDocStrings(s.getClass().getClassLoader())
                                                             .entrySet().stream())
                             .collect(toImmutableMap(Map.Entry<String, String>::getKey,
                                                     (Map.Entry<String, String> entry) ->
                                                             DescriptionInfo.of(entry.getValue()),
                                                     (a, b) -> a));
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
    ServiceSpecification generate(List<ServiceInfo> services,
                                  DescriptiveTypeInfoProvider descriptiveTypeInfoProvider) {
        return ServiceSpecification.generate(
                services, typeSignature -> newDescriptiveTypeInfo(typeSignature, descriptiveTypeInfoProvider));
    }

    private static DescriptiveTypeInfo newDescriptiveTypeInfo(
            DescriptiveTypeSignature typeSignature, DescriptiveTypeInfoProvider descriptiveTypeInfoProvider) {
        final Object descriptor = typeSignature.descriptor();
        assert descriptor instanceof Descriptor || descriptor instanceof EnumDescriptor;
        final DescriptiveTypeInfo descriptiveTypeInfo =
                descriptiveTypeInfoProvider.newDescriptiveTypeInfo(descriptor);
        return requireNonNull(descriptiveTypeInfo,
                              "descriptiveTypeInfoProvider.newDescriptiveTypeInfo() returned null");
    }

    @VisibleForTesting
    static MethodInfo newMethodInfo(String serviceName, MethodDescriptor method,
                                    Set<EndpointInfo> endpointInfos) {
        return new MethodInfo(
                serviceName,
                method.getName(),
                // gRPC methods always take a single request parameter of message type.
                descriptiveMessageSignature(method.getOutputType()),
                ImmutableList.of(FieldInfo.builder("request",
                                                   descriptiveMessageSignature(method.getInputType()))
                                          .requirement(FieldRequirement.REQUIRED).build()),
                true, ImmutableList.of(),
                endpointInfos,
                ImmutableList.of(),
                defaultExamples(method),
                ImmutableList.of(),
                ImmutableList.of(),
                HttpMethod.POST, DescriptionInfo.empty());
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
                    service.findMethodByName(grpcMethod.getBareMethodName());
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
                                     .map(method -> newMethodInfo(serviceName, method,
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
