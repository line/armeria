/*
 * Copyright 2021 LINE Corporation
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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.AnnotationsProto;
import com.google.api.HttpRule;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.internal.server.RouteUtil;
import com.linecorp.armeria.internal.server.grpc.HttpEndpointSpecification;
import com.linecorp.armeria.internal.server.grpc.HttpEndpointSpecification.Parameter;
import com.linecorp.armeria.internal.server.grpc.HttpEndpointSupport;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RouteBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.GrpcTranscodingPathParser.PathSegment;
import com.linecorp.armeria.server.grpc.GrpcTranscodingPathParser.PathSegment.PathMappingType;
import com.linecorp.armeria.server.grpc.GrpcTranscodingPathParser.Stringifier;
import com.linecorp.armeria.server.grpc.GrpcTranscodingPathParser.VariablePathSegment;
import com.linecorp.armeria.server.grpc.GrpcTranscodingService.PathVariable.ValueDefinition.Type;

import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoMethodDescriptorSupplier;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;

/**
 * Converts HTTP/JSON request to gRPC request and delegates it to the {@link FramedGrpcService}.
 */
final class GrpcTranscodingService extends AbstractUnframedGrpcService
        implements HttpEndpointSupport {
    private static final Logger logger = LoggerFactory.getLogger(GrpcTranscodingService.class);

    /**
     * Create a new {@link GrpcService} instance from the given {@code delegate}. If it is possible
     * to support HTTP/JSON to gRPC transcoding, a new {@link GrpcTranscodingService} instance
     * would be returned. Otherwise, the {@code delegate} would be returned.
     */
    static GrpcService of(GrpcService delegate, UnframedGrpcErrorHandler unframedGrpcErrorHandler) {
        requireNonNull(delegate, "delegate");

        final ImmutableMap.Builder<Route, TranscodingSpec> builder = ImmutableMap.builder();

        final List<ServerServiceDefinition> serviceDefinitions = delegate.services();
        for (ServerServiceDefinition serviceDefinition : serviceDefinitions) {
            final Descriptors.ServiceDescriptor serviceDesc = serviceDescriptor(serviceDefinition);
            if (serviceDesc == null) {
                continue;
            }

            for (ServerMethodDefinition<?, ?> methodDefinition : serviceDefinition.getMethods()) {
                final Descriptors.MethodDescriptor methodDesc = methodDescriptor(methodDefinition);
                if (methodDesc == null) {
                    continue;
                }

                final MethodOptions methodOptions = methodDesc.getOptions();
                if (!methodOptions.hasExtension(AnnotationsProto.http)) {
                    continue;
                }

                final HttpRule httpRule = methodOptions.getExtension(AnnotationsProto.http);
                @Nullable
                final Entry<Route, List<PathVariable>> routeAndVariables = toRouteAndPathVariables(httpRule);
                if (routeAndVariables == null) {
                    continue;
                }

                final Route route = routeAndVariables.getKey();
                final List<PathVariable> pathVariables = routeAndVariables.getValue();

                final Map<String, Field> fields = buildFields(methodDesc.getInputType(), ImmutableList.of());

                int order = 0;
                builder.put(route, new TranscodingSpec(order++, httpRule,
                                                       serviceDefinition, methodDefinition,
                                                       serviceDesc, methodDesc, fields, pathVariables));

                for (HttpRule additionalHttpRule : httpRule.getAdditionalBindingsList()) {
                    @Nullable
                    final Entry<Route, List<PathVariable>> additionalRouteAndVariables
                            = toRouteAndPathVariables(additionalHttpRule);
                    if (additionalRouteAndVariables != null) {
                        builder.put(additionalRouteAndVariables.getKey(),
                                    new TranscodingSpec(order++, additionalHttpRule,
                                                        serviceDefinition, methodDefinition,
                                                        serviceDesc, methodDesc, fields,
                                                        additionalRouteAndVariables.getValue()));
                    }
                }
            }
        }

        final Map<Route, TranscodingSpec> routeAndSpecs = builder.build();
        if (routeAndSpecs.isEmpty()) {
            // We don't need to create a new HttpToGrpcTranscodingService instance in this case.
            return delegate;
        }
        return new GrpcTranscodingService(delegate, routeAndSpecs, unframedGrpcErrorHandler);
    }

    @Nullable
    private static ServiceDescriptor serviceDescriptor(ServerServiceDefinition serviceDefinition) {
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

    @VisibleForTesting
    @Nullable
    static Entry<Route, List<PathVariable>> toRouteAndPathVariables(HttpRule httpRule) {
        final RouteBuilder builder = Route.builder();
        final String path;
        switch (httpRule.getPatternCase()) {
            case GET:
                builder.methods(HttpMethod.GET);
                path = httpRule.getGet();
                break;
            case PUT:
                builder.methods(HttpMethod.PUT);
                path = httpRule.getPut();
                break;
            case POST:
                builder.methods(HttpMethod.POST);
                path = httpRule.getPost();
                break;
            case DELETE:
                builder.methods(HttpMethod.DELETE);
                path = httpRule.getDelete();
                break;
            case PATCH:
                builder.methods(HttpMethod.PATCH);
                path = httpRule.getPatch();
                break;
            case CUSTOM:
            default:
                logger.warn("Ignoring unsupported route pattern: pattern={}, httpRule={}",
                            httpRule.getPatternCase(), httpRule);
                return null;
        }

        // Check whether the path is Armeria-native.
        if (path.startsWith(RouteUtil.EXACT) ||
            path.startsWith(RouteUtil.PREFIX) ||
            path.startsWith(RouteUtil.GLOB) ||
            path.startsWith(RouteUtil.REGEX)) {

            final Route route = builder.path(path).build();
            final List<PathVariable> vars =
                    route.paramNames().stream()
                         .map(name -> new PathVariable(null, name,
                                                       ImmutableList.of(
                                                               new PathVariable.ValueDefinition(Type.REFERENCE,
                                                                                                name))))
                         .collect(toImmutableList());
            return new SimpleImmutableEntry<>(route, vars);
        }

        final List<PathSegment> segments = GrpcTranscodingPathParser.parse(path);

        final PathMappingType pathMappingType =
                segments.stream().allMatch(segment -> segment.support(PathMappingType.PARAMETERIZED)) ?
                PathMappingType.PARAMETERIZED : PathMappingType.GLOB;

        if (pathMappingType == PathMappingType.PARAMETERIZED) {
            builder.path(Stringifier.asParameterizedPath(segments));
        } else {
            builder.glob(Stringifier.asGlobPath(segments));
        }
        return new SimpleImmutableEntry<>(builder.build(), PathVariable.from(segments, pathMappingType));
    }

    private static Map<String, Field> buildFields(Descriptor desc, List<String> parentNames) {
        final StringJoiner namePrefixJoiner = new StringJoiner(".");
        parentNames.forEach(namePrefixJoiner::add);
        final String namePrefix = namePrefixJoiner.length() == 0 ? "" : namePrefixJoiner.toString() + '.';

        final ImmutableMap.Builder<String, Field> builder = ImmutableMap.builder();
        desc.getFields().forEach(field -> {
            final JavaType type = field.getJavaType();
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
                    builder.put(namePrefix + field.getName(), new Field(field, parentNames));
                    break;
                case MESSAGE:
                    @Nullable
                    final Descriptor typeDesc =
                            desc.getNestedTypes().stream()
                                .filter(d -> d.getFullName().equals(field.getMessageType().getFullName()))
                                .findFirst()
                                // Find global type if there is no nested type.
                                .orElseGet(() -> desc.getFile()
                                                     .findMessageTypeByName(field.getMessageType().getName()));
                    checkState(typeDesc != null,
                               "Descriptor for the type '%s' does not exist.",
                               field.getMessageType().getFullName());
                    builder.putAll(buildFields(typeDesc, ImmutableList.<String>builder()
                                                                      .addAll(parentNames)
                                                                      .add(field.getName())
                                                                      .build()));
                    break;
            }
        });
        return builder.build();
    }

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private final Map<Route, TranscodingSpec> routeAndSpecs;
    private final Set<Route> routes;

    private GrpcTranscodingService(GrpcService delegate,
                                   Map<Route, TranscodingSpec> routeAndSpecs,
                                   UnframedGrpcErrorHandler unframedGrpcErrorHandler) {
        super(delegate, unframedGrpcErrorHandler);
        this.routeAndSpecs = routeAndSpecs;
        routes = ImmutableSet.<Route>builder()
                             .addAll(delegate.routes())
                             .addAll(routeAndSpecs.keySet())
                             .build();
    }

    @Override
    public HttpEndpointSpecification httpEndpointSpecification(Route route) {
        requireNonNull(route, "route");
        final TranscodingSpec spec = routeAndSpecs.get(route);
        if (spec == null) {
            return null;
        }
        final Set<String> paramNames = spec.pathVariables.stream().map(PathVariable::name)
                                                         .collect(toImmutableSet());
        final Map<String, Parameter> parameterTypes =
                spec.fields.entrySet().stream().collect(
                        toImmutableMap(Entry::getKey,
                                       fieldEntry -> new Parameter(fieldEntry.getValue().type(),
                                                                   fieldEntry.getValue().isRepeated())));
        return new HttpEndpointSpecification(spec.order,
                                             route,
                                             paramNames,
                                             spec.serviceDescriptor,
                                             spec.methodDescriptor,
                                             parameterTypes,
                                             spec.httpRule);
    }

    /**
     * Returns the {@link Route}s which are supported by this service and the {@code delegate}.
     */
    @Override
    public Set<Route> routes() {
        return routes;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final Route mappedRoute = ctx.config().route();
        final TranscodingSpec spec = routeAndSpecs.get(mappedRoute);
        if (spec != null) {
            return serve0(ctx, req, spec);
        }
        return unwrap().serve(ctx, req);
    }

    private HttpResponse serve0(ServiceRequestContext ctx, HttpRequest req,
                                TranscodingSpec spec) throws Exception {
        final RequestHeaders clientHeaders = req.headers();

        if (spec.method.getMethodDescriptor().getType() != MethodType.UNARY) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST,
                                   MediaType.PLAIN_TEXT_UTF_8,
                                   "Only unary methods can be used with non-framed requests.");
        }

        final RequestHeadersBuilder grpcHeaders = clientHeaders.toBuilder();

        grpcHeaders.method(HttpMethod.POST)
                   .contentType(GrpcSerializationFormats.JSON.mediaType());

        if (grpcHeaders.get(GrpcHeaderNames.GRPC_ENCODING) != null) {
            return HttpResponse.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                                   MediaType.PLAIN_TEXT_UTF_8,
                                   "gRPC encoding is not supported for non-framed requests.");
        }

        // All clients support no encoding, and we don't support gRPC encoding for non-framed requests, so just
        // clear the header if it's present.
        grpcHeaders.remove(GrpcHeaderNames.GRPC_ACCEPT_ENCODING);

        ctx.logBuilder().defer(RequestLogProperty.REQUEST_CONTENT,
                               RequestLogProperty.RESPONSE_CONTENT);

        final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        req.aggregateWithPooledObjects(ctx.eventLoop(), ctx.alloc()).handle((clientRequest, t) -> {
            try (SafeCloseable ignore = ctx.push()) {
                if (t != null) {
                    responseFuture.completeExceptionally(t);
                } else {
                    try {
                        ctx.setAttr(FramedGrpcService.RESOLVED_GRPC_METHOD, spec.method);
                        frameAndServe(unwrap(), ctx, grpcHeaders.build(),
                                      convertToJson(ctx, clientRequest, spec),
                                      responseFuture);
                    } catch (Exception e) {
                        responseFuture.completeExceptionally(e);
                    }
                }
            }
            return null;
        });
        return HttpResponse.from(responseFuture);
    }

    /**
     * Converts the HTTP request to gRPC JSON with the {@link TranscodingSpec}.
     */
    private static HttpData convertToJson(ServiceRequestContext ctx,
                                          AggregatedHttpRequest request,
                                          TranscodingSpec spec) throws IOException {
        try {
            switch (request.method()) {
                case GET:
                    return setParametersAndWriteJson(mapper.createObjectNode(), ctx, spec);
                case PUT:
                case POST:
                case PATCH:
                case DELETE:
                    final String bodyMapping = spec.httpRule.getBody();
                    // Put the body into the json if 'body: "*"' is specified.
                    if ("*".equals(bodyMapping)) {
                        @Nullable
                        final JsonNode body = getBodyContent(request);
                        final ObjectNode root;
                        if (body instanceof ObjectNode) {
                            root = (ObjectNode) body;
                        } else {
                            root = mapper.createObjectNode();
                        }
                        return setParametersAndWriteJson(root, ctx, spec);
                    }

                    // Put the body into the json under "name" field if 'body: "name"' is specified.
                    final ObjectNode root = mapper.createObjectNode();
                    if (!Strings.isNullOrEmpty(bodyMapping)) {
                        ObjectNode current = root;
                        final String[] nameParts = bodyMapping.split("\\.");
                        for (int i = 0; i < nameParts.length - 1; i++) {
                            current = current.putObject(nameParts[i]);
                        }
                        @Nullable
                        final JsonNode body = getBodyContent(request);
                        if (body != null) {
                            current.set(nameParts[nameParts.length - 1], body);
                        } else {
                            current.putNull(nameParts[nameParts.length - 1]);
                        }
                    }
                    return setParametersAndWriteJson(root, ctx, spec);
                default:
                    throw HttpStatusException.of(HttpStatus.METHOD_NOT_ALLOWED);
            }
        } finally {
            request.content().close();
        }
    }

    @Nullable
    private static JsonNode getBodyContent(AggregatedHttpRequest request) {
        @Nullable
        final MediaType contentType = request.contentType();
        if (contentType == null || !contentType.isJson()) {
            return null;
        }
        try {
            return mapper.readTree(request.contentUtf8());
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @VisibleForTesting
    static Map<String, String> populatePathVariables(ServiceRequestContext ctx,
                                                     List<PathVariable> pathVariables) {
        return pathVariables.stream().map(var -> {
            final String value =
                    var.values().stream()
                       .map(def -> {
                           if (def.type == Type.REFERENCE) {
                               return ctx.pathParam(def.value);
                           } else {
                               return def.value;
                           }
                       }).collect(Collectors.joining("/"));
            return new SimpleImmutableEntry<>(var.name(), value);
        }).collect(toImmutableMap(Entry::getKey, Entry::getValue));
    }

    private static HttpData setParametersAndWriteJson(ObjectNode root,
                                                      ServiceRequestContext ctx,
                                                      TranscodingSpec spec) throws JsonProcessingException {
        // Generate path variable name/value map.
        final Map<String, String> resolvedPathVars = populatePathVariables(ctx, spec.pathVariables);

        setParametersToNode(root, resolvedPathVars.entrySet(), spec);
        if (ctx.query() != null) {
            setParametersToNode(root, QueryParams.fromQueryString(ctx.query()), spec);
        }
        return HttpData.copyOf(mapper.writeValueAsBytes(root));
    }

    private static void setParametersToNode(ObjectNode root,
                                            Iterable<Entry<String, String>> parameters,
                                            TranscodingSpec spec) {
        for (Map.Entry<String, String> entry : parameters) {
            final Field field = spec.fields.get(entry.getKey());
            if (field == null) {
                // Ignore unknown parameters.
                continue;
            }
            ObjectNode currentNode = root;
            for (String parentName : field.parentNames) {
                final JsonNode node = currentNode.get(parentName);
                if (node != null) {
                    if (!node.isObject()) {
                        // It should be an ObjectNode but it may not when a user sent a wrong JSON document
                        // in the HTTP body with HTTP POST, PUT, PATCH or DELETE methods.
                        throw new IllegalArgumentException(
                                "Request body may not follow the protocol specification.");
                    }
                    currentNode = (ObjectNode) node;
                } else {
                    currentNode = currentNode.putObject(parentName);
                }
            }

            // If the field has a 'repeated' label, we should treat it as a JSON array node.
            if (field.isRepeated()) {
                final ArrayNode arrayNode;
                final JsonNode node = currentNode.get(field.name());
                if (node != null) {
                    if (!node.isArray()) {
                        // It should be an ArrayNode but it may not when a user sent a wrong JSON document
                        // in the HTTP body with HTTP POST, PUT, PATCH or DELETE methods.
                        throw new IllegalArgumentException(
                                "Request body may not follow the protocol specification.");
                    }
                    arrayNode = (ArrayNode) node;
                } else {
                    arrayNode = currentNode.putArray(field.name());
                }
                // If a request has multiple values for a query parameter like 'param=foo&param=bar&param=baz',
                // the following JSON would be generated.
                // { "param": ["foo", "bar", "baz"] }
                setValueToArrayNode(arrayNode, field, entry.getValue());
            } else {
                setValueToObjectNode(currentNode, field, entry.getValue());
            }
        }
    }

    private static void setValueToArrayNode(ArrayNode node, Field field, String value) {
        switch (field.type()) {
            case INT:
                node.add(Integer.parseInt(value));
                break;
            case LONG:
                node.add(Long.parseLong(value));
                break;
            case FLOAT:
                node.add(Float.parseFloat(value));
                break;
            case DOUBLE:
                node.add(Double.parseDouble(value));
                break;
            case BOOLEAN:
                node.add(Boolean.parseBoolean(value));
                break;
            case STRING:
            case BYTE_STRING:
            case ENUM:
                node.add(value);
                break;
        }
    }

    private static void setValueToObjectNode(ObjectNode node, Field field, String value) {
        switch (field.type()) {
            case INT:
                node.put(field.name(), Integer.parseInt(value));
                break;
            case LONG:
                node.put(field.name(), Long.parseLong(value));
                break;
            case FLOAT:
                node.put(field.name(), Float.parseFloat(value));
                break;
            case DOUBLE:
                node.put(field.name(), Double.parseDouble(value));
                break;
            case BOOLEAN:
                node.put(field.name(), Boolean.parseBoolean(value));
                break;
            case STRING:
            case BYTE_STRING:
            case ENUM:
                node.put(field.name(), value);
                break;
        }
    }

    /**
     * Details of HTTP/JSON to gRPC transcoding.
     */
    static final class TranscodingSpec {
        private final int order;
        private final HttpRule httpRule;
        private final ServerServiceDefinition service;
        private final ServerMethodDefinition<?, ?> method;
        private final Descriptors.ServiceDescriptor serviceDescriptor;
        private final Descriptors.MethodDescriptor methodDescriptor;
        private final Map<String, Field> fields;
        private final List<PathVariable> pathVariables;

        private TranscodingSpec(int order,
                                HttpRule httpRule,
                                ServerServiceDefinition service,
                                ServerMethodDefinition<?, ?> method,
                                ServiceDescriptor serviceDescriptor,
                                MethodDescriptor methodDescriptor,
                                Map<String, Field> fields,
                                List<PathVariable> pathVariables) {
            this.order = order;
            this.httpRule = httpRule;
            this.service = service;
            this.method = method;
            this.serviceDescriptor = serviceDescriptor;
            this.methodDescriptor = methodDescriptor;
            this.fields = fields;
            this.pathVariables = pathVariables;
        }
    }

    /**
     * gRPC field definition.
     */
    static final class Field {
        private final FieldDescriptor descriptor;
        private final List<String> parentNames;

        private Field(FieldDescriptor descriptor, List<String> parentNames) {
            this.descriptor = descriptor;
            this.parentNames = parentNames;
        }

        JavaType type() {
            return descriptor.getJavaType();
        }

        String name() {
            return descriptor.getJsonName();
        }

        boolean isRepeated() {
            return descriptor.isRepeated();
        }
    }

    /**
     * A path variable defined in the path of {@code google.api.http} option.
     */
    static final class PathVariable {

        /**
         * Collects {@link PathVariable}s from the parsed {@link PathSegment}s.
         */
        static List<PathVariable> from(List<PathSegment> segments,
                                       PathSegment.PathMappingType type) {
            return segments.stream()
                           .filter(segment -> segment instanceof VariablePathSegment)
                           .flatMap(segment -> resolvePathVariables(null, (VariablePathSegment) segment, type)
                                   .stream())
                           .collect(toImmutableList());
        }

        private static List<PathVariable> resolvePathVariables(@Nullable String parent,
                                                               VariablePathSegment var,
                                                               PathSegment.PathMappingType type) {
            final ImmutableList.Builder<PathVariable> pathVariables = ImmutableList.builder();
            final ImmutableList.Builder<ValueDefinition> valueDefinitions = ImmutableList.builder();
            var.valueSegments().forEach(segment -> {
                if (segment instanceof VariablePathSegment) {
                    final List<PathVariable> children =
                            resolvePathVariables(var.fieldPath(), (VariablePathSegment) segment, type);
                    // Flatten value definitions which include the way how to get the value of the variable.
                    // Example:
                    //   - original path: "/v1/hello/{name=foo/{age=*}/{country=*}}"
                    //   - parsed path: "/v1/hello/foo/:age/:country"
                    //   - variables:
                    //     - name: foo, :age, :country
                    //     - age: :age
                    //     - country: :country
                    children.stream()
                            .filter(child -> var.fieldPath().equals(child.parent()))
                            .forEach(child -> valueDefinitions.addAll(child.values()));
                    pathVariables.addAll(children);
                } else {
                    @Nullable
                    final String v = segment.pathVariable(type);
                    if (v != null) {
                        valueDefinitions.add(new ValueDefinition(Type.REFERENCE, v));
                    } else {
                        valueDefinitions.add(new ValueDefinition(Type.LITERAL, segment.segmentString(type)));
                    }
                }
            });
            return pathVariables.add(new PathVariable(parent, var.fieldPath(), valueDefinitions.build()))
                                .build();
        }

        @Nullable
        private final String parent;
        private final String name;
        private final List<ValueDefinition> values;

        PathVariable(@Nullable String parent,
                     String name,
                     List<ValueDefinition> values) {
            this.parent = parent;
            this.name = requireNonNull(name, "name");
            this.values = requireNonNull(values, "values");
        }

        @Nullable
        String parent() {
            return parent;
        }

        String name() {
            return name;
        }

        List<ValueDefinition> values() {
            return values;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("parent", parent)
                              .add("name", name)
                              .add("values", values)
                              .toString();
        }

        static final class ValueDefinition {
            private final Type type;
            private final String value;

            ValueDefinition(Type type, String value) {
                this.type = requireNonNull(type, "type");
                this.value = requireNonNull(value, "value");
            }

            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this)
                                  .add("type", type)
                                  .add("value", value)
                                  .toString();
            }

            enum Type {
                /**
                 * Uses the {@code value} as a literal.
                 */
                LITERAL,
                /**
                 * Needs to get the value of {@link ServiceRequestContext#pathParam(String)} with
                 * the {@code value}.
                 */
                REFERENCE
            }
        }
    }
}
