/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.server.annotation;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.armeria.server.annotation.AnnotatedHttpDocServiceUtil.COOKIE_PARAM;
import static com.linecorp.armeria.server.annotation.AnnotatedHttpDocServiceUtil.extractParameter;
import static com.linecorp.armeria.server.annotation.AnnotatedHttpDocServiceUtil.getNormalizedTriePath;
import static com.linecorp.armeria.server.annotation.AnnotatedHttpDocServiceUtil.isHidden;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.server.HttpHeaderPathMapping;
import com.linecorp.armeria.server.ServiceConfig;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.util.AnnotationsUtils;
import io.swagger.v3.core.util.ReflectionUtils;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.links.Link;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.ServerVariable;
import io.swagger.v3.oas.models.servers.ServerVariables;
import io.swagger.v3.oas.models.tags.Tag;

/**
 * A utility class that parses annotated HTTP services and builds an {@link OpenAPI} from it.
 * This class uses annotations defined in Armeria such as {@link Param}, {@link Header}, {@link Produces},
 * {@link Consumes}, etc, as well as the annotations in Swagger.
 *
 * @see <a href="https://github.com/OAI/OpenAPI-Specification/blob/3.0.1/versions/3.0.1.md#oasObject">
 *     OpenAPI specification</a>
 */
public final class AnnotatedOpenApiReader {

    private static final String DEFAULT_RESPONSE = "default response";

    private static final Pattern PATH_VARIABLE_PATTERN = Pattern.compile("(?:\\{\\w+\\})");

    /**
     * Returns an {@link OpenAPI} by reading the annotations in the specified annotated HTTP services.
     */
    public static OpenAPI read(Iterable<ServiceConfig> serviceConfigs) {
        requireNonNull(serviceConfigs, "serviceConfigs");
        final OpenAPI openApi = new OpenAPI().components(new Components());
        Streams.stream(serviceConfigs).map(sc -> sc.service().as(AnnotatedHttpService.class))
               .filter(Optional::isPresent).map(Optional::get).forEach(
                service -> fillFromService(openApi, service));
        return openApi;
    }

    @VisibleForTesting
    static void fillFromService(OpenAPI openApi, AnnotatedHttpService service) {
        if (isHidden(service)) {
            return;
        }

        final HttpHeaderPathMapping pathMapping = service.pathMapping();
        final String operationPath = endpointPath(pathMapping);
        if (isNullOrEmpty(operationPath)) {
            return;
        }

        final Class<?> clazz = service.object().getClass();
        fillFromOpenApiDefinition(openApi, clazz);

        final Operation operation = operation(openApi, clazz, service,
                                              consumeTypes(pathMapping), produceTypes(pathMapping));

        final PathItem pathItem = pathItem(openApi, operationPath);
        openApi.path(operationPath, pathItem);
        pathMapping.supportedMethods().forEach(
                httpMethod -> setPathItemOperation(openApi, pathItem, httpMethod, operation));
    }

    @Nullable
    @VisibleForTesting
    static String endpointPath(HttpHeaderPathMapping pathMapping) {
        if (pathMapping.prefix().isPresent()) {
            if (pathMapping.regex().isPresent()) { // Does not support regex.
                return null;
            }
            return pathMapping.prefix().get();
        }
        return getNormalizedTriePath(pathMapping);
    }

    @VisibleForTesting
    static void fillFromOpenApiDefinition(OpenAPI openApi, Class<?> clazz) {
        final OpenAPIDefinition definition = ReflectionUtils.getAnnotation(clazz, OpenAPIDefinition.class);
        if (definition == null) {
            return;
        }

        AnnotationsUtils.getInfo(definition.info()).ifPresent(openApi::setInfo);
        AnnotationsUtils.getExternalDocumentation(definition.externalDocs())
                        .ifPresent(extDoc -> {
                            validateExternalDocument(extDoc);
                            openApi.setExternalDocs(extDoc);
                        });

        final Builder<Tag> builder = ImmutableSet.builder();
        AnnotationsUtils.getTags(definition.tags(), false).ifPresent(tags -> {
            tags.forEach(tag -> {
                final io.swagger.v3.oas.models.ExternalDocumentation externalDocs = tag.getExternalDocs();
                if (externalDocs != null) {
                    validateExternalDocument(externalDocs);
                }
            });
            builder.addAll(tags);
        });
        final ImmutableSet<Tag> newTags = builder.build();
        if (!newTags.isEmpty()) {
            final List<Tag> tags = ImmutableList.copyOf(uniqueTags(openApi.getTags(), newTags));
            openApi.setTags(tags);
        }

        AnnotationsUtils.getServers(definition.servers()).ifPresent(servers -> {
            validateServers(servers);
            openApi.setServers(servers);
        });
    }

    private static void validateExternalDocument(io.swagger.v3.oas.models.ExternalDocumentation extDoc) {
        if (isNullOrEmpty(extDoc.getUrl())) {
            throw new IllegalStateException(
                    "A URL is required for external documentation: " + extDoc);
        }
    }

    private static Set<Tag> uniqueTags(@Nullable List<Tag> openApiTags, Set<Tag> newTags) {
        final Set<Tag> tagSet = new LinkedHashSet<>();
        if (openApiTags != null) {
            tagSet.addAll(openApiTags);
        }

        for (Tag tag : newTags) {
            if (tagSet.stream().noneMatch(t -> t.getName().equals(tag.getName()))) {
                tagSet.add(tag);
            }
        }
        return tagSet;
    }

    private static void validateServers(Iterable<io.swagger.v3.oas.models.servers.Server> servers) {
        for (io.swagger.v3.oas.models.servers.Server server : servers) {
            final ServerVariables serverVariables = server.getVariables();
            final Matcher matcher = PATH_VARIABLE_PATTERN.matcher(server.getUrl());
            while (matcher.find()) {
                final String group = matcher.group();
                final String variable = group.substring(1, group.length() - 1);
                if (!serverVariables.containsKey(variable)) {
                    throw new IllegalStateException("The path variable:'" + variable + "' in the URL:'" +
                                                    server.getUrl() +
                                                    "' is not specified in the server variable.");
                }
            }

            for (ServerVariable serverVariable : serverVariables.values()) {
                if (isNullOrEmpty(serverVariable.getDefault())) {
                    throw new IllegalStateException(
                            "Server variable should have default property. server variable:" + serverVariable);
                }
            }
        }
    }

    @VisibleForTesting
    static Operation operation(OpenAPI openApi, Class<?> clazz, AnnotatedHttpService service,
                               String[] consumeTypes, String[] produceTypes) {
        final Operation operation = new Operation();
        final Method method = service.method();
        operation.setOperationId(uniqueOperationId(openApi, method.getName()));
        setExternalDocs(method, operation);

        addTags(method, operation);
        final io.swagger.v3.oas.annotations.responses.ApiResponse[] classResponses = ReflectionUtils
                .getRepeatableAnnotationsArray(
                        clazz, io.swagger.v3.oas.annotations.responses.ApiResponse.class);
        if (classResponses != null && classResponses.length > 0) {
            apiResponses(classResponses, produceTypes, openApi.getComponents()).ifPresent(
                    operation::setResponses);
        }

        setOperationFromAnnotation(openApi, method, operation, consumeTypes, produceTypes);
        final Set<String> classTags = classTags(clazz);
        classTags.stream().filter(t -> operation.getTags() == null ||
                                       (operation.getTags() != null && !operation.getTags().contains(t)))
                 .forEach(operation::addTagsItem);

        addRequestBodyFromAnnotation(openApi, method, operation, consumeTypes);
        addApiResponsesFromAnnotation(openApi, method, operation, produceTypes);
        addReturnType(openApi, method, operation, produceTypes);
        if (ReflectionUtils.getAnnotation(method, Deprecated.class) != null) {
            operation.deprecated(true);
        }

        final List<AnnotatedValueResolver> resolvers = service.annotatedValueResolvers();
        final List<Parameter> parameters = parameters(resolvers, openApi.getComponents());
        if (parameters.size() > 0) {
            parameters.forEach(operation::addParametersItem);
        }

        return operation;
    }

    private static String uniqueOperationId(OpenAPI openApi, String operationId) {
        boolean operationIdUsed = isOperationIdUsed(openApi, operationId);
        String operationIdToFind = null;
        int counter = 0;
        while (operationIdUsed) {
            operationIdToFind = String.format("%s_%d", operationId, ++counter);
            operationIdUsed = isOperationIdUsed(openApi, operationIdToFind);
        }
        if (operationIdToFind != null) {
            operationId = operationIdToFind;
        }
        return operationId;
    }

    private static boolean isOperationIdUsed(OpenAPI openApi, String operationId) {
        if (openApi.getPaths() == null || openApi.getPaths().isEmpty()) {
            return false;
        }
        for (PathItem path : openApi.getPaths().values()) {
            final String pathOperationId = operationIdFromPathItem(path);
            if (operationId.equalsIgnoreCase(pathOperationId)) {
                return true;
            }
        }
        return false;
    }

    private static String operationIdFromPathItem(PathItem path) {
        if (path.getGet() != null) {
            return path.getGet().getOperationId();
        } else if (path.getPost() != null) {
            return path.getPost().getOperationId();
        } else if (path.getPut() != null) {
            return path.getPut().getOperationId();
        } else if (path.getDelete() != null) {
            return path.getDelete().getOperationId();
        } else if (path.getOptions() != null) {
            return path.getOptions().getOperationId();
        } else if (path.getHead() != null) {
            return path.getHead().getOperationId();
        } else if (path.getPatch() != null) {
            return path.getPatch().getOperationId();
        }
        return "";
    }

    private static void setExternalDocs(Method method, Operation operation) {
        final ExternalDocumentation extDocAnnotation = ReflectionUtils
                .getAnnotation(method, ExternalDocumentation.class);
        AnnotationsUtils.getExternalDocumentation(extDocAnnotation).ifPresent(extDoc -> {
            validateExternalDocument(extDoc);
            operation.setExternalDocs(extDoc);
        });
    }

    private static void addTags(Method method, Operation operation) {
        final List<io.swagger.v3.oas.annotations.tags.Tag> tagAnnotations =
                ReflectionUtils.getRepeatableAnnotations(method, io.swagger.v3.oas.annotations.tags.Tag.class);
        if (tagAnnotations != null) {
            tagAnnotations.stream()
                          .filter(t -> operation.getTags() == null ||
                                       (operation.getTags() != null && !operation.getTags().contains(t.name())))
                          .map(io.swagger.v3.oas.annotations.tags.Tag::name)
                          .forEach(operation::addTagsItem);
        }
    }

    private static String[] consumeTypes(HttpHeaderPathMapping pathMapping) {
        return pathMapping.consumeTypes().stream()
                          .map(com.linecorp.armeria.common.MediaType::toString)
                          .toArray(String[]::new);
    }

    private static String[] produceTypes(HttpHeaderPathMapping pathMapping) {
        return pathMapping.produceTypes().stream()
                          .map(com.linecorp.armeria.common.MediaType::toString)
                          .toArray(String[]::new);
    }

    private static Optional<ApiResponses> apiResponses(
            io.swagger.v3.oas.annotations.responses.ApiResponse[] apiResponseAnnotations,
            String[] produceTypes, Components components) {
        final ApiResponses apiResponses = new ApiResponses();
        for (io.swagger.v3.oas.annotations.responses.ApiResponse responseAnnotation : apiResponseAnnotations) {
            final ApiResponse apiResponse = new ApiResponse();
            final String description = responseAnnotation.description();
            if (isNullOrEmpty(description)) {
                throw new IllegalStateException("A description is required for the response:" + apiResponse);
            }
            apiResponse.setDescription(description);
            if (responseAnnotation.extensions().length > 0) {
                AnnotationsUtils.getExtensions(responseAnnotation.extensions())
                                .forEach(apiResponse::addExtension);
            }
            AnnotationsUtils.getContent(responseAnnotation.content(), new String[0],
                                        produceTypes, null, components, null)
                            .ifPresent(apiResponse::content);
            AnnotationsUtils.getHeaders(responseAnnotation.headers(), null)
                            .ifPresent(apiResponse::headers);

            if (!isNullOrEmpty(apiResponse.getDescription()) ||
                apiResponse.getContent() != null || apiResponse.getHeaders() != null) {
                final Map<String, Link> links = AnnotationsUtils.getLinks(responseAnnotation.links());
                if (links.size() > 0) {
                    apiResponse.setLinks(links);
                }

                final String responseCode = responseAnnotation.responseCode();
                if (!isNullOrEmpty(responseCode)) {
                    apiResponses.addApiResponse(responseCode, apiResponse);
                } else {
                    apiResponses.setDefault(apiResponse);
                }
            }
        }

        return apiResponses.isEmpty() ? Optional.empty() : Optional.of(apiResponses);
    }

    private static void setOperationFromAnnotation(OpenAPI openApi, Method method, Operation operation,
                                                   String[] consumeTypes, String[] produceTypes) {
        final io.swagger.v3.oas.annotations.Operation operationAnnotation =
                ReflectionUtils.getAnnotation(method, io.swagger.v3.oas.annotations.Operation.class);
        if (operationAnnotation != null) {
            final String summary = operationAnnotation.summary();
            if (!isNullOrEmpty(summary)) {
                operation.setSummary(summary);
            }
            final String description = operationAnnotation.description();
            if (!isNullOrEmpty(description)) {
                operation.setDescription(description);
            }
            final String operationId = operationAnnotation.operationId();
            if (!isNullOrEmpty(operationId)) {
                operation.setOperationId(uniqueOperationId(openApi, operationId));
            }
            if (operationAnnotation.deprecated()) {
                operation.setDeprecated(operationAnnotation.deprecated());
            }

            Stream.of(operationAnnotation.tags())
                  .filter(t -> operation.getTags() == null ||
                               (operation.getTags() != null && !operation.getTags().contains(t)))
                  .forEach(operation::addTagsItem);

            if (operation.getExternalDocs() == null) {
                AnnotationsUtils.getExternalDocumentation(operationAnnotation.externalDocs())
                                .ifPresent(extDoc -> {
                                    validateExternalDocument(extDoc);
                                    operation.setExternalDocs(extDoc);
                                });
            }
            requestBody(operationAnnotation.requestBody(), consumeTypes, openApi.getComponents())
                    .ifPresent(operation::setRequestBody);

            apiResponses(operationAnnotation.responses(), produceTypes, openApi.getComponents())
                    .ifPresent(responses -> {
                        if (operation.getResponses() == null) {
                            operation.setResponses(responses);
                        } else {
                            responses.forEach(operation.getResponses()::addApiResponse);
                        }
                    });
        }
    }

    private static Optional<RequestBody> requestBody(
            io.swagger.v3.oas.annotations.parameters.RequestBody requestBodyAnnotation,
            String[] consumes, Components components) {
        final Optional<Content> content = AnnotationsUtils.getContent(requestBodyAnnotation.content(),
                                                                      new String[0], consumes, null,
                                                                      components, null);
        if (!content.isPresent()) {
            return Optional.empty();
        }

        final RequestBody requestBody = new RequestBody();
        requestBody.content(content.get());
        requestBody.required(requestBodyAnnotation.required());
        if (!isNullOrEmpty(requestBodyAnnotation.description())) {
            requestBody.description(requestBodyAnnotation.description());
        }
        return Optional.of(requestBody);
    }

    private static Set<String> classTags(Class<?> clazz) {
        final io.swagger.v3.oas.annotations.tags.Tag[] tagAnnotations = ReflectionUtils
                .getRepeatableAnnotationsArray(clazz, io.swagger.v3.oas.annotations.tags.Tag.class);
        if (tagAnnotations == null) {
            return ImmutableSet.of();
        }
        final Builder<String> classTagBuilder = ImmutableSet.builder();
        AnnotationsUtils.getTags(tagAnnotations, false)
                        .ifPresent(tags -> tags.stream().map(Tag::getName).forEach(classTagBuilder::add));
        return classTagBuilder.build();
    }

    private static void addRequestBodyFromAnnotation(OpenAPI openApi, Method method,
                                                     Operation operation, String[] consumeTypes) {
        final io.swagger.v3.oas.annotations.parameters.RequestBody requestBodyAnnotation = ReflectionUtils
                .getAnnotation(method, io.swagger.v3.oas.annotations.parameters.RequestBody.class);

        if (requestBodyAnnotation == null) {
            return;
        }

        requestBody(requestBodyAnnotation, consumeTypes, openApi.getComponents())
                .ifPresent(operation::setRequestBody);
    }

    private static void addApiResponsesFromAnnotation(OpenAPI openApi, Method method,
                                                      Operation operation, String[] produceTypes) {
        final List<io.swagger.v3.oas.annotations.responses.ApiResponse> apiResponseAnnotations =
                ReflectionUtils.getRepeatableAnnotations(
                        method, io.swagger.v3.oas.annotations.responses.ApiResponse.class);

        if (apiResponseAnnotations == null || apiResponseAnnotations.size() == 0) {
            return;
        }

        apiResponses(apiResponseAnnotations.toArray(
                new io.swagger.v3.oas.annotations.responses.ApiResponse[apiResponseAnnotations.size()]),
                     produceTypes, openApi.getComponents())
                .ifPresent(responses -> {
                    if (operation.getResponses() == null) {
                        operation.setResponses(responses);
                    } else {
                        responses.forEach(operation.getResponses()::addApiResponse);
                    }
                });
    }

    private static void addReturnType(OpenAPI openApi, Method method, Operation operation, String[] produces) {
        final Type returnType = method.getGenericReturnType();
        if (!returnType.equals(Void.TYPE)) {
            final ResolvedSchema resolvedSchema = ModelConverters.getInstance().resolveAsResolvedSchema(
                    new AnnotatedType(returnType).resolveAsRef(true));
            if (resolvedSchema.schema != null) {
                final Schema returnTypeSchema = resolvedSchema.schema;
                final Content content = new Content();
                final MediaType mediaType = new MediaType().schema(returnTypeSchema);
                // Add mediaType to content
                AnnotationsUtils.applyTypes(null, produces, content, mediaType);
                if (operation.getResponses() == null) {
                    final ApiResponses apiResponses = new ApiResponses();
                    apiResponses.setDefault(new ApiResponse().description(DEFAULT_RESPONSE).content(content));
                    operation.setResponses(apiResponses);
                }

                final ApiResponse defaultResponse = operation.getResponses().getDefault();
                if (defaultResponse != null && isNullOrEmpty(defaultResponse.get$ref())) {
                    final Content defaultContent = defaultResponse.getContent();
                    if (defaultContent == null) {
                        defaultResponse.content(content);
                    } else {
                        for (String key : defaultContent.keySet()) {
                            if (defaultContent.get(key).getSchema() == null) {
                                defaultContent.get(key).setSchema(returnTypeSchema);
                            }
                        }
                    }
                }
                final Map<String, Schema> schemaMap = resolvedSchema.referencedSchemas;
                if (schemaMap != null) {
                    schemaMap.forEach(openApi.getComponents()::addSchemas);
                }
            }
            if (operation.getResponses() == null || operation.getResponses().isEmpty()) {
                // Responses Object MUST contain at least one response object, so add an empty default response
                final Content content = new Content();
                AnnotationsUtils.applyTypes(null, produces, content, new MediaType());
                final ApiResponses apiResponses = new ApiResponses();
                apiResponses.setDefault(new ApiResponse().description(DEFAULT_RESPONSE).content(content));
                operation.setResponses(apiResponses);
            }
        }
    }

    static List<Parameter> parameters(List<AnnotatedValueResolver> resolvers, Components components) {

        final ImmutableList.Builder<Parameter> builder = ImmutableList.builder();
        for (AnnotatedValueResolver resolver : resolvers) {
            final Parameter parameter = extractParameter(resolver);
            if (parameter == null) {
                continue;
            }

            if (parameter.getIn().equals(COOKIE_PARAM)) {
                parameter.setSchema(new StringSchema());
            } else {
                final AnnotatedType annotatedType = new AnnotatedType().type(resolver.elementType())
                                                                       .resolveAsRef(true)
                                                                       .skipOverride(true);
                final ResolvedSchema resolvedSchema = ModelConverters.getInstance()
                                                                     .resolveAsResolvedSchema(annotatedType);
                if (resolvedSchema.schema != null) {
                    parameter.setSchema(resolvedSchema.schema);
                }
                final Map<String, Schema> schemaMap = resolvedSchema.referencedSchemas;
                if (schemaMap != null) {
                    schemaMap.forEach(components::addSchemas);
                }
            }
            builder.add(parameter);
        }
        return builder.build();
    }

    private static PathItem pathItem(OpenAPI openApi, String operationPath) {
        final Paths paths = openApi.getPaths();
        if (paths != null && paths.get(operationPath) != null) {
            return paths.get(operationPath);
        }
        final PathItem pathItem = new PathItem();
        openApi.path(operationPath, pathItem);
        return pathItem;
    }

    private static void setPathItemOperation(OpenAPI openApi, PathItem pathItem, HttpMethod method,
                                             Operation operation) {
        if (isOperationIdUsed(openApi, operation.getOperationId())) {
            operation = copyOperationWithNewId(openApi, operation);
        }

        switch (method) {
            case POST:
                pathItem.post(operation);
                break;
            case GET:
                pathItem.get(operation);
                break;
            case DELETE:
                pathItem.delete(operation);
                break;
            case PUT:
                pathItem.put(operation);
                break;
            case PATCH:
                pathItem.patch(operation);
                break;
            case TRACE:
                pathItem.trace(operation);
                break;
            case HEAD:
                pathItem.head(operation);
                break;
            case OPTIONS:
                pathItem.options(operation);
                break;
            default:
                // Do nothing here
                break;
        }
    }

    private static Operation copyOperationWithNewId(OpenAPI openApi, Operation operation) {
        final Operation newOperation = new Operation().deprecated(operation.getDeprecated())
                                                      .parameters(operation.getParameters())
                                                      .tags(operation.getTags())
                                                      .responses(operation.getResponses())
                                                      .requestBody(operation.getRequestBody())
                                                      .externalDocs(operation.getExternalDocs())
                                                      .description(operation.getDescription())
                                                      .servers(operation.getServers())
                                                      .summary(operation.getSummary());
        newOperation.setOperationId(uniqueOperationId(openApi, operation.getOperationId()));
        return newOperation;
    }

    private AnnotatedOpenApiReader() {}
}
