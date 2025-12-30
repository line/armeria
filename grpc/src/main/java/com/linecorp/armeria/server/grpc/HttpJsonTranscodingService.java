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
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
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
import com.google.api.HttpBody;
import com.google.api.HttpRule;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CaseFormat;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.internal.server.grpc.HttpEndpointSpecification;
import com.linecorp.armeria.internal.server.grpc.HttpEndpointSpecification.Parameter;
import com.linecorp.armeria.internal.server.grpc.HttpEndpointSupport;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingPathParser.PathSegment;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingPathParser.VariablePathSegment;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingService.PathVariable.ValueDefinition.Type;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.grpc.ServerMethodDefinition;

/**
 * Converts HTTP/JSON request to gRPC request and delegates it to the {@link FramedGrpcService}.
 */
final class HttpJsonTranscodingService extends AbstractUnframedGrpcService
        implements HttpEndpointSupport {
    private static final Logger logger = LoggerFactory.getLogger(HttpJsonTranscodingService.class);

    @Nullable
    private static Function<AggregatedHttpResponse, AggregatedHttpResponse> generateResponseConverter(
            TranscodingSpec spec) {
        // Ignore the spec if the method is HttpBody. The response body is already in the correct format.
        if (HttpBody.getDescriptor().equals(spec.methodDescriptor.getOutputType())) {
            return httpResponse -> {
                final HttpData data = httpResponse.content();
                final JsonNode jsonNode = extractHttpBody(data);

                // Failed to parse the JSON body, return the original response.
                if (jsonNode == null) {
                    return httpResponse;
                }

                PooledObjects.close(data);

                // The data field is base64 encoded.
                // https://protobuf.dev/programming-guides/proto3/#json
                final String httpBody = jsonNode.get("data").asText();
                final byte[] httpBodyBytes = Base64.getDecoder().decode(httpBody);

                final ResponseHeaders newHeaders = httpResponse.headers().withMutations(builder -> {
                    final JsonNode contentType = jsonNode.get("contentType");

                    if (contentType != null && contentType.isTextual()) {
                        builder.set(HttpHeaderNames.CONTENT_TYPE, contentType.textValue());
                    } else {
                        builder.remove(HttpHeaderNames.CONTENT_TYPE);
                    }
                });

                return AggregatedHttpResponse.of(newHeaders, HttpData.wrap(httpBodyBytes));
            };
        }

        @Nullable
        final String responseBody = spec.responseBody;
        if (responseBody == null) {
            return null;
        }

        return httpResponse -> {
            try (HttpData data = httpResponse.content()) {
                final HttpData convertedData = convertHttpDataForResponseBody(responseBody, data);
                return AggregatedHttpResponse.of(httpResponse.headers(), convertedData);
            }
        };
    }

    @Nullable
    private static JsonNode extractHttpBody(HttpData data) {
        final byte[] array = data.array();

        try {
            return mapper.readValue(array, JsonNode.class);
        } catch (IOException e) {
            logger.warn("Unexpected exception while parsing HttpBody from {}", data, e);
            return null;
        }
    }

    private static HttpData convertHttpDataForResponseBody(String responseBody, HttpData data) {
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

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private final Map<Route, TranscodingSpec> routeAndSpecs;
    private final Set<Route> routes;

    HttpJsonTranscodingService(GrpcService delegate,
                               Map<Route, TranscodingSpec> routeAndSpecs,
                               HttpJsonTranscodingOptions httpJsonTranscodingOptions) {
        super(delegate, httpJsonTranscodingOptions.errorHandler());
        this.routeAndSpecs = routeAndSpecs;

        final LinkedHashSet<Route> linkedHashSet = new LinkedHashSet<>(delegate.routes().size() +
                                                                       routeAndSpecs.size());
        linkedHashSet.addAll(delegate.routes());

        routeAndSpecs.entrySet().stream().sorted((o1, o2) -> {
            if (o1.getValue().hasVerb) {
                return -1;
            }
            if (o2.getValue().hasVerb) {
                return 1;
            }
            return 0;
        }).forEach(entry -> {
            linkedHashSet.add(entry.getKey());
        });

        routes = Collections.unmodifiableSet(linkedHashSet);
    }

    @Nullable
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
                spec.originalFields.entrySet().stream().collect(
                        toImmutableMap(Entry::getKey,
                                       fieldEntry -> new Parameter(fieldEntry.getValue().type(),
                                                                   fieldEntry.getValue().isRepeated(),
                                                                   fieldEntry.getValue().isRequired())));
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

    @Nullable
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

        grpcHeaders.method(HttpMethod.POST)
                   .contentType(GrpcSerializationFormats.JSON.mediaType());
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
                        final HttpData requestContent;

                        // https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_filters/grpc_json_transcoder_filter#sending-arbitrary-content
                        if (HttpBody.getDescriptor().equals(spec.methodDescriptor.getInputType())) {
                            // Convert the HTTP request to a JSON representation of HttpBody.
                            requestContent = convertToHttpBody(clientRequest);
                        } else {
                            // Convert the HTTP request to gRPC JSON.
                            requestContent = convertToJson(ctx, clientRequest, spec);
                        }

                        frameAndServe(unwrap(), ctx, grpcHeaders.build(),
                                      requestContent, responseFuture,
                                      generateResponseConverter(spec));
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
        return HttpResponse.of(responseFuture);
    }

    private static HttpData convertToHttpBody(AggregatedHttpRequest request) throws IOException {
        final ObjectNode body = mapper.createObjectNode();

        try (HttpData content = request.content()) {
            final MediaType contentType;

            @Nullable
            final MediaType requestContentType = request.contentType();
            if (requestContentType != null) {
                contentType = requestContentType;
            } else {
                contentType = MediaType.OCTET_STREAM;
            }

            body.put("content_type", contentType.toString());
            // Jackson converts byte array to base64 string. gRPC transcoding spec also returns base64 string.
            // https://protobuf.dev/programming-guides/proto3/#json
            body.put("data", content.array());

            return HttpData.wrap(mapper.writeValueAsBytes(body));
        }
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
                        } else if (body == null) {
                            root = mapper.createObjectNode();
                        } else {
                            throw new IllegalArgumentException("Unexpected JSON: " +
                                                               body + ", (expected: ObjectNode or null).");
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
        final HttpData bodyContent = request.content();
        final boolean hasBodyContent = !bodyContent.isEmpty();

        if (contentType != null && contentType.isJson()) {
            if (!hasBodyContent) {
                return mapper.createObjectNode();
            }

            try {
                return mapper.readTree(bodyContent.toStringUtf8());
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Failed to parse JSON request.", e);
            }
        }

        if (hasBodyContent) {
            throw new IllegalArgumentException("Missing or invalid content-type in JSON request.");
        }

        return null;
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

        setParametersToNode(root, resolvedPathVars.entrySet(), spec, true);
        final QueryParams params = ctx.queryParams();
        if (!params.isEmpty()) {
            setParametersToNode(root, params, spec, false);
        }
        return HttpData.wrap(mapper.writeValueAsBytes(root));
    }

    private static void setParametersToNode(ObjectNode root,
                                            Iterable<Entry<String, String>> parameters,
                                            TranscodingSpec spec, boolean pathVariables) {
        for (Map.Entry<String, String> entry : parameters) {
            Field field = null;
            if (pathVariables) {
                // The original field name should be used for the path variable
                // Syntax: Variable = "{" FieldPath [ "=" Segments ] "}" ;
                field = spec.originalFields.get(entry.getKey());
            } else {
                // A query parameter can be matched with one of an original field name, a camel case name or
                // a json name depending on the `HttpJsonTranscodingOptions`.
                for (Map<String, Field> mappingFields : spec.queryMappingFields) {
                    field = mappingFields.get(entry.getKey());
                    if (field != null) {
                        break;
                    }
                }
            }
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
        private final Map<String, Field> originalFields;
        private final List<Map<String, Field>> queryMappingFields;
        private final List<PathVariable> pathVariables;
        private final boolean hasVerb;
        @Nullable
        private final String responseBody;

        TranscodingSpec(int order,
                        HttpRule httpRule,
                        ServerMethodDefinition<?, ?> method,
                        ServiceDescriptor serviceDescriptor,
                        MethodDescriptor methodDescriptor,
                        Map<String, Field> originalFields,
                        List<Map<String, Field>> queryMappingFields,
                        List<PathVariable> pathVariables,
                        boolean hasVerb, @Nullable String responseBody) {
            this.order = order;
            this.httpRule = httpRule;
            this.method = method;
            this.serviceDescriptor = serviceDescriptor;
            this.methodDescriptor = methodDescriptor;
            this.originalFields = originalFields;
            this.queryMappingFields = queryMappingFields;
            this.pathVariables = pathVariables;
            this.hasVerb = hasVerb;
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
        private final boolean isRequired;

        Field(FieldDescriptor descriptor,
              List<String> parentNames,
              JavaType javaType,
              boolean isRequired) {
            this.descriptor = descriptor;
            this.parentNames = parentNames;
            this.javaType = javaType;
            this.isRequired = isRequired;
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

        boolean isRequired() {
            return isRequired;
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
}
