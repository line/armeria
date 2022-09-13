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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
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
import com.google.common.base.CaseFormat;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.Duration;
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
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingPathParser.PathSegment;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingPathParser.PathSegment.PathMappingType;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingPathParser.Stringifier;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingPathParser.VariablePathSegment;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingService.PathVariable.ValueDefinition.Type;

import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoMethodDescriptorSupplier;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;
import io.netty.util.internal.StringUtil;

/**
 * Converts HTTP/JSON request to gRPC request and delegates it to the {@link FramedGrpcService}.
 */
final class HttpJsonTranscodingService extends AbstractUnframedGrpcService
        implements HttpEndpointSupport {
    private static final Logger logger = LoggerFactory.getLogger(HttpJsonTranscodingService.class);

    /**
     * Creates a new {@link GrpcService} instance from the given {@code delegate}. If it is possible
     * to support HTTP/JSON to gRPC transcoding, a new {@link HttpJsonTranscodingService} instance
     * would be returned. Otherwise, the {@code delegate} would be returned.
     */
    static GrpcService of(GrpcService delegate, UnframedGrpcErrorHandler unframedGrpcErrorHandler,
                          HttpJsonTranscodingOptions httpJsonTranscodingOptions) {
        requireNonNull(delegate, "delegate");
        requireNonNull(unframedGrpcErrorHandler, "unframedGrpcErrorHandler");
        requireNonNull(httpJsonTranscodingOptions, "httpJsonTranscodingOptions");

        final Map<Route, TranscodingSpec> specs = new HashMap<>();

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

                checkArgument(methodDefinition.getMethodDescriptor().getType() == MethodType.UNARY,
                              "Only unary methods can be configured with an HTTP/JSON endpoint: " +
                              "method=%s, httpRule=%s",
                              methodDefinition.getMethodDescriptor().getFullMethodName(), httpRule);

                @Nullable
                final Entry<Route, List<PathVariable>> routeAndVariables = toRouteAndPathVariables(httpRule);
                if (routeAndVariables == null) {
                    continue;
                }

                final Route route = routeAndVariables.getKey();
                final List<PathVariable> pathVariables = routeAndVariables.getValue();
                final Map<String, Field> fields =
                        buildFields(methodDesc.getInputType(), ImmutableList.of(), ImmutableSet.of(),
                                    httpJsonTranscodingOptions.camelCaseQueryParams());

                if (specs.containsKey(route)) {
                    logger.warn("{} is not added because the route is duplicate: {}", httpRule, route);
                    continue;
                }
                final List<FieldDescriptor> topLevelFields = methodDesc.getOutputType().getFields();
                final String responseBody = getResponseBody(topLevelFields, httpRule.getResponseBody());
                int order = 0;
                specs.put(route, new TranscodingSpec(order++, httpRule, methodDefinition,
                                                     serviceDesc, methodDesc, fields, pathVariables,
                                                     responseBody));
                for (HttpRule additionalHttpRule : httpRule.getAdditionalBindingsList()) {
                    @Nullable
                    final Entry<Route, List<PathVariable>> additionalRouteAndVariables
                            = toRouteAndPathVariables(additionalHttpRule);
                    if (additionalRouteAndVariables != null) {
                        specs.put(additionalRouteAndVariables.getKey(),
                                    new TranscodingSpec(order++, additionalHttpRule, methodDefinition,
                                                        serviceDesc, methodDesc, fields,
                                                        additionalRouteAndVariables.getValue(),
                                                        responseBody));
                    }
                }
            }
        }

        if (specs.isEmpty()) {
            // We don't need to create a new HttpJsonTranscodingService instance in this case.
            return delegate;
        }
        return new HttpJsonTranscodingService(delegate, ImmutableMap.copyOf(specs), unframedGrpcErrorHandler);
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

        final List<PathSegment> segments = HttpJsonTranscodingPathParser.parse(path);

        final PathMappingType pathMappingType =
                segments.stream().allMatch(segment -> segment.support(PathMappingType.PARAMETERIZED)) ?
                PathMappingType.PARAMETERIZED : PathMappingType.GLOB;

        if (pathMappingType == PathMappingType.PARAMETERIZED) {
            builder.path(Stringifier.asParameterizedPath(segments, true));
        } else {
            builder.glob(Stringifier.asGlobPath(segments, true));
        }
        return new SimpleImmutableEntry<>(builder.build(), PathVariable.from(segments, pathMappingType));
    }

    private static Map<String, Field> buildFields(Descriptor desc,
                                                  List<String> parentNames,
                                                  Set<Descriptor> visitedTypes,
                                                  boolean useCamelCaseKeys) {
        final StringJoiner namePrefixJoiner = new StringJoiner(".");
        parentNames.forEach(namePrefixJoiner::add);
        final String namePrefix = namePrefixJoiner.length() == 0 ? "" : namePrefixJoiner.toString() + '.';

        final ImmutableMap.Builder<String, Field> builder = ImmutableMap.builder();
        desc.getFields().forEach(field -> {
            final JavaType type = field.getJavaType();
            String key = namePrefix + field.getName();
            if (useCamelCaseKeys) {
                key = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, key);
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
                    builder.put(namePrefix + field.getName(),
                                new Field(field, parentNames, field.getJavaType()));
                    break;
                case MESSAGE:
                    @Nullable
                    final JavaType wellKnownFieldType = getJavaTypeForWellKnownTypes(field);
                    if (wellKnownFieldType != null) {
                        builder.put(namePrefix + field.getName(),
                                    new Field(field, parentNames, wellKnownFieldType));
                        break;
                    }

                    if (visitedTypes.contains(field.getMessageType())) {
                        // Found recursion. No more analysis for this type.
                        // Raise an exception in order to mark the root parameter as JavaType.MESSAGE.
                        throw new RecursiveTypeException(field.getMessageType());
                    }

                    @Nullable
                    Descriptor typeDesc =
                            desc.getNestedTypes().stream()
                                .filter(d -> d.getFullName().equals(field.getMessageType().getFullName()))
                                .findFirst().orElse(null);
                    if (typeDesc == null) {
                        // From the proto file.
                        typeDesc = findTypeDescriptor(desc.getFile(), field);
                    }
                    if (typeDesc == null) {
                        // According to the Language guide, the public import functionality is not available
                        // in Java. We will try to find dependencies only with "import" keyword.
                        // https://developers.google.com/protocol-buffers/docs/proto3#importing_definitions
                        typeDesc = desc.getFile().getDependencies().stream()
                                       .map(fd -> findTypeDescriptor(fd, field))
                                       .filter(Objects::nonNull).findFirst().orElse(null);
                    }
                    checkState(typeDesc != null,
                               "Descriptor for the type '%s' does not exist.",
                               field.getMessageType().getFullName());
                    try {
                        builder.putAll(buildFields(typeDesc,
                                                   ImmutableList.<String>builder()
                                                                .addAll(parentNames)
                                                                .add(field.getName())
                                                                .build(),
                                                   ImmutableSet.<Descriptor>builder()
                                                               .addAll(visitedTypes)
                                                               .add(field.getMessageType())
                                                               .build(),
                                                   useCamelCaseKeys));
                    } catch (RecursiveTypeException e) {
                        if (e.recursiveTypeDescriptor() != field.getMessageType()) {
                            // Re-throw the exception if it is not caused by my field.
                            throw e;
                        }

                        builder.put(namePrefix + field.getName(),
                                    new Field(field, parentNames, JavaType.MESSAGE));
                    }
                    break;
            }
        });
        return builder.build();
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
            Duration.getDescriptor().getFullName().equals(fullName)) {
            return JavaType.STRING;
        }

        if (DoubleValue.getDescriptor().getFullName().equals(fullName) ||
            FloatValue.getDescriptor().getFullName().equals(fullName) ||
            Int64Value.getDescriptor().getFullName().equals(fullName) ||
            UInt64Value.getDescriptor().getFullName().equals(fullName) ||
            Int32Value.getDescriptor().getFullName().equals(fullName) ||
            UInt32Value.getDescriptor().getFullName().equals(fullName) ||
            BoolValue.getDescriptor().getFullName().equals(fullName) ||
            StringValue.getDescriptor().getFullName().equals(fullName) ||
            BytesValue.getDescriptor().getFullName().equals(fullName)) {
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

    @Nullable
    private static Descriptor findTypeDescriptor(FileDescriptor file, FieldDescriptor field) {
        final Descriptor messageType = field.getMessageType();
        if (!file.getPackage().equals(messageType.getFile().getPackage())) {
            return null;
        }
        return file.findMessageTypeByName(messageType.getName());
    }

    // to make it more efficient, we calculate whether extract response body one time
    // if there is no matching toplevel field, we set it to null
    @Nullable
    private static String getResponseBody(List<FieldDescriptor> topLevelFields,
                                          @Nullable String responseBody) {
        if (StringUtil.isNullOrEmpty(responseBody)) {
            return null;
        }
        for (FieldDescriptor fieldDescriptor: topLevelFields) {
            if (fieldDescriptor.getName().equals(responseBody)) {
                return responseBody;
            }
        }
        return null;
    }

    @Nullable
    private static Function<HttpData, HttpData> generateResponseBodyConverter(TranscodingSpec spec) {
        @Nullable final String responseBody = spec.responseBody;
        if (responseBody == null) {
            return null;
        } else {
            return httpData -> {
                try (HttpData data = httpData) {
                    final byte[] array = data.array();
                    try {
                        final JsonNode jsonNode = mapper.readValue(array, JsonNode.class);
                        // we try to convert lower snake case response body to camel case
                        final String lowerCamelCaseResponseBody =
                                CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, responseBody);
                        final Iterator<Entry<String, JsonNode>> fields = jsonNode.fields();
                        while (fields.hasNext()) {
                            final Entry<String, JsonNode> entry = fields.next();
                            final String fieldName = entry.getKey();
                            final JsonNode responseBodyJsonNode = entry.getValue();
                            // try to match field name and response body
                            // 1. by default the marshaller would use lowerCamelCase in json field
                            // 2. when the marshaller use original name in .proto file when serializing messages
                            if (fieldName.equals(lowerCamelCaseResponseBody) ||
                                fieldName.equals(responseBody)) {
                                final byte[] bytes = mapper.writeValueAsBytes(responseBodyJsonNode);
                                return HttpData.wrap(bytes);
                            }
                        }
                        return HttpData.ofUtf8("null");
                    } catch (IOException e) {
                        logger.warn("Unexpected exception while extracting responseBody '{}' from {}",
                                    responseBody, data, e);
                        return HttpData.wrap(array);
                    }
                }
            };
        }
    }

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private final Map<Route, TranscodingSpec> routeAndSpecs;
    private final Set<Route> routes;

    private HttpJsonTranscodingService(GrpcService delegate,
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
    public ServerMethodDefinition<?, ?> methodDefinition(ServiceRequestContext ctx) {
        final TranscodingSpec spec = routeAndSpecs.get(ctx.config().mappedRoute());
        if (spec != null) {
            return spec.method;
        }
        return super.methodDefinition(ctx);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final TranscodingSpec spec = routeAndSpecs.get(ctx.config().mappedRoute());
        if (spec != null) {
            return serve0(ctx, req, spec);
        }
        return unwrap().serve(ctx, req);
    }

    private HttpResponse serve0(ServiceRequestContext ctx, HttpRequest req,
                                TranscodingSpec spec) throws Exception {
        final RequestHeaders clientHeaders = req.headers();
        final RequestHeadersBuilder grpcHeaders = clientHeaders.toBuilder();
        if (grpcHeaders.get(GrpcHeaderNames.GRPC_ENCODING) != null) {
            return HttpResponse.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                                   MediaType.PLAIN_TEXT_UTF_8,
                                   "gRPC encoding is not supported for non-framed requests.");
        }

        final MediaType jsonContentType = GrpcSerializationFormats.JSON.mediaType();
        grpcHeaders.method(HttpMethod.POST)
                   .contentType(jsonContentType);
        // All clients support no encoding, and we don't support gRPC encoding for non-framed requests, so just
        // clear the header if it's present.
        grpcHeaders.remove(GrpcHeaderNames.GRPC_ACCEPT_ENCODING);

        ctx.logBuilder().defer(RequestLogProperty.REQUEST_CONTENT,
                               RequestLogProperty.RESPONSE_CONTENT);

        final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        req.aggregate(ctx.eventLoop()).handle((clientRequest, t) -> {
            try (SafeCloseable ignore = ctx.push()) {
                if (t != null) {
                    responseFuture.completeExceptionally(t);
                } else {
                    try {
                        ctx.setAttr(FramedGrpcService.RESOLVED_GRPC_METHOD, spec.method);
                        frameAndServe(unwrap(), ctx, grpcHeaders.build(),
                                      convertToJson(ctx, clientRequest, spec),
                                      responseFuture, generateResponseBodyConverter(spec), jsonContentType);
                    } catch (IllegalArgumentException iae) {
                        responseFuture.completeExceptionally(
                                HttpStatusException.of(HttpStatus.BAD_REQUEST, iae));
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
        return HttpData.wrap(mapper.writeValueAsBytes(root));
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

            if (field.javaType == JavaType.MESSAGE) {
                throw new IllegalArgumentException(
                        "Unsupported message type: " + field.descriptor.getFullName());
            }

            ObjectNode currentNode = root;
            for (String parentName : field.parentNames) {
                final JsonNode node = currentNode.get(parentName);
                if (node != null) {
                    // It should be an ObjectNode but it may not if a user sent a wrong JSON document
                    // in the HTTP body with HTTP POST, PUT, PATCH or DELETE methods.
                    checkArgument(node.isObject(), "Invalid request body (must be a JSON object)");
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
                    // It should be an ArrayNode but it may not if a user sent a wrong JSON document
                    // in the HTTP body with HTTP POST, PUT, PATCH or DELETE methods.
                    checkArgument(node.isArray(), "Invalid request body (must be a JSON array)");
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
        private final ServerMethodDefinition<?, ?> method;
        private final Descriptors.ServiceDescriptor serviceDescriptor;
        private final Descriptors.MethodDescriptor methodDescriptor;
        private final Map<String, Field> fields;
        private final List<PathVariable> pathVariables;
        @Nullable
        private final String responseBody;

        private TranscodingSpec(int order,
                                HttpRule httpRule,
                                ServerMethodDefinition<?, ?> method,
                                ServiceDescriptor serviceDescriptor,
                                MethodDescriptor methodDescriptor,
                                Map<String, Field> fields,
                                List<PathVariable> pathVariables,
                                @Nullable String responseBody) {
            this.order = order;
            this.httpRule = httpRule;
            this.method = method;
            this.serviceDescriptor = serviceDescriptor;
            this.methodDescriptor = methodDescriptor;
            this.fields = fields;
            this.pathVariables = pathVariables;
            this.responseBody = responseBody;
        }
    }

    /**
     * gRPC field definition.
     */
    static final class Field {
        private final FieldDescriptor descriptor;
        private final List<String> parentNames;
        private final JavaType javaType;

        private Field(FieldDescriptor descriptor, List<String> parentNames, JavaType javaType) {
            this.descriptor = descriptor;
            this.parentNames = parentNames;
            this.javaType = javaType;
        }

        JavaType type() {
            return javaType;
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
                           .filter(VariablePathSegment.class::isInstance)
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
