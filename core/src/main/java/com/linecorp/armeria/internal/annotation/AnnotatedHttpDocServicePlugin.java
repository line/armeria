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

package com.linecorp.armeria.internal.annotation;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.linecorp.armeria.internal.PathMappingUtil.EXACT;
import static com.linecorp.armeria.internal.PathMappingUtil.PREFIX;
import static com.linecorp.armeria.internal.PathMappingUtil.REGEX;
import static com.linecorp.armeria.internal.annotation.AnnotatedHttpDocServiceUtil.getNormalizedTriePath;
import static com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceFactory.findDescription;
import static com.linecorp.armeria.server.docs.FieldLocation.HEADER;
import static com.linecorp.armeria.server.docs.FieldLocation.PATH;
import static com.linecorp.armeria.server.docs.FieldLocation.QUERY;
import static com.linecorp.armeria.server.docs.FieldLocation.UNSPECIFIED;
import static java.util.Objects.requireNonNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.annotation.AnnotatedBeanFactoryRegistry.BeanFactoryId;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.docs.DocServicePlugin;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.EndpointInfoBuilder;
import com.linecorp.armeria.server.docs.EnumInfo;
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

/**
 * A {@link DocServicePlugin} implementation that supports the {@link AnnotatedHttpService}.
 */
public final class AnnotatedHttpDocServicePlugin implements DocServicePlugin {

    // The formats defined by OpenAPI Specification
    // (https://github.com/OAI/OpenAPI-Specification/blob/master/versions/3.0.2.md#dataTypes):
    @VisibleForTesting
    static final TypeSignature VOID = TypeSignature.ofBase("void");
    @VisibleForTesting
    static final TypeSignature BOOLEAN = TypeSignature.ofBase("boolean");
    @VisibleForTesting
    static final TypeSignature INT = TypeSignature.ofBase("int");
    @VisibleForTesting
    static final TypeSignature LONG = TypeSignature.ofBase("long");
    @VisibleForTesting
    static final TypeSignature FLOAT = TypeSignature.ofBase("float");
    @VisibleForTesting
    static final TypeSignature DOUBLE = TypeSignature.ofBase("double");
    @VisibleForTesting
    static final TypeSignature STRING = TypeSignature.ofBase("string");
    @VisibleForTesting
    static final TypeSignature BINARY = TypeSignature.ofBase("binary");

    // Not defined in the spec.
    @VisibleForTesting
    static final TypeSignature BYTE = TypeSignature.ofBase("byte");
    @VisibleForTesting
    static final TypeSignature SHORT = TypeSignature.ofBase("short");
    @VisibleForTesting
    static final TypeSignature BEAN = TypeSignature.ofBase("bean");

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Set<Class<? extends Service<?, ?>>> supportedServiceTypes() {
        return ImmutableSet.of(AnnotatedHttpService.class);
    }

    @Override
    public ServiceSpecification generateSpecification(Set<ServiceConfig> serviceConfigs) {
        requireNonNull(serviceConfigs, "serviceConfigs");
        final Map<Class<?>, Set<MethodInfo>> methodInfos = new HashMap<>();
        final Map<Class<?>, String> serviceDescription = new HashMap<>();
        serviceConfigs.forEach(sc -> {
            final Optional<AnnotatedHttpService> service = sc.service().as(AnnotatedHttpService.class);
            service.ifPresent(
                    httpService -> {
                        addMethodInfo(methodInfos, sc.virtualHost().hostnamePattern(), httpService);
                        addServiceDescription(serviceDescription, httpService);
                    });
        });

        return generate(serviceDescription, methodInfos);
    }

    private static void addServiceDescription(Map<Class<?>, String> serviceDescription,
                                              AnnotatedHttpService service) {
        final Class<?> clazz = service.object().getClass();
        serviceDescription.computeIfAbsent(clazz, AnnotatedHttpServiceFactory::findDescription);
    }

    private static void addMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos,
                                      String hostnamePattern, AnnotatedHttpService service) {
        final PathMapping pathMapping = service.pathMapping();
        final EndpointInfo endpoint = endpointInfo(pathMapping, hostnamePattern);
        if (endpoint == null) {
            return;
        }

        final Method method = service.method();
        final String name = method.getName();
        final TypeSignature returnTypeSignature = toTypeSignature(method.getGenericReturnType());
        final List<FieldInfo> fieldInfos = fieldInfos(service.annotatedValueResolvers());
        final Class<?> clazz = service.object().getClass();
        pathMapping.supportedMethods().forEach(
                httpMethod -> {
                    final MethodInfo methodInfo = new MethodInfo(
                            name, returnTypeSignature, fieldInfos, ImmutableList.of(), // Ignore exceptions.
                            ImmutableList.of(endpoint), httpMethod, findDescription(method));
                    methodInfos.computeIfAbsent(clazz, unused -> new HashSet<>()).add(methodInfo);
                });
    }

    @Nullable
    @VisibleForTesting
    static EndpointInfo endpointInfo(PathMapping pathMapping, String hostnamePattern) {
        final String endpointPathMapping = endpointPathMapping(pathMapping);
        if (isNullOrEmpty(endpointPathMapping)) {
            return null;
        }

        final EndpointInfoBuilder builder = new EndpointInfoBuilder(hostnamePattern, endpointPathMapping);
        if (endpointPathMapping.startsWith(REGEX) && pathMapping.prefix().isPresent()) {
            // PrefixAddingPathMapping
            builder.regexPathPrefix(PREFIX + pathMapping.prefix().get());
        }

        builder.availableMimeTypes(availableMimeTypes(pathMapping));
        return builder.build();
    }

    private static String endpointPathMapping(PathMapping pathMapping) {
        final Optional<String> exactPath = pathMapping.exactPath();
        if (exactPath.isPresent()) {
            return EXACT + exactPath.get();
        }

        final Optional<String> regex = pathMapping.regex();
        if (regex.isPresent()) {
            return REGEX + regex.get();
        }

        final Optional<String> prefix = pathMapping.prefix();
        if (prefix.isPresent()) {
            return PREFIX + prefix.get();
        }

        return getNormalizedTriePath(pathMapping);
    }

    private static List<MediaType> availableMimeTypes(PathMapping pathMapping) {
        final Builder<MediaType> builder = ImmutableList.builder();
        final List<MediaType> consumeTypes = pathMapping.consumeTypes();
        builder.addAll(consumeTypes);
        if (!consumeTypes.contains(MediaType.JSON_UTF_8)) {
            builder.add(MediaType.JSON_UTF_8);
        }
        return builder.build();
    }

    private static List<FieldInfo> fieldInfos(List<AnnotatedValueResolver> resolvers) {
        final ImmutableList.Builder<FieldInfo> fieldInfosBuilder = ImmutableList.builder();
        for (AnnotatedValueResolver resolver : resolvers) {
            final FieldInfo fieldInfo = fieldInfo(resolver);
            if (fieldInfo != null) {
                fieldInfosBuilder.add(fieldInfo);
            }
        }
        return fieldInfosBuilder.build();
    }

    @Nullable
    private static FieldInfo fieldInfo(AnnotatedValueResolver resolver) {
        final Class<? extends Annotation> annotationType = resolver.annotationType();
        if (annotationType == RequestObject.class) {
            final BeanFactoryId beanFactoryId = resolver.beanFactoryId();
            final Optional<AnnotatedBeanFactory<?>> optional =
                    AnnotatedBeanFactoryRegistry.find(beanFactoryId);
            if (optional.isPresent()) {
                final AnnotatedBeanFactory<?> factory = optional.get();
                final Builder<AnnotatedValueResolver> builder = ImmutableList.builder();
                factory.constructor().getValue().forEach(builder::add);
                factory.methods().values().forEach(resolvers -> resolvers.forEach(builder::add));
                factory.fields().values().forEach(builder::add);
                final List<AnnotatedValueResolver> resolvers = builder.build();
                if (!resolvers.isEmpty()) {
                    // Just use the simple name of the bean class as the field name.
                    return new FieldInfoBuilder(beanFactoryId.type().getSimpleName(), BEAN,
                                                fieldInfos(resolvers)).build();
                }
            }
            return null;
        }

        if (annotationType != Param.class && annotationType != Header.class) {
            return null;
        }
        final TypeSignature signature;
        if (resolver.hasContainer()) {
            final Class<?> containerType = resolver.containerType();
            assert containerType != null;
            final TypeSignature parameterTypeSignature = toTypeSignature(resolver.elementType());
            if (List.class.isAssignableFrom(containerType)) {
                signature = TypeSignature.ofList(parameterTypeSignature);
            } else if (Set.class.isAssignableFrom(containerType)) {
                signature = TypeSignature.ofSet(parameterTypeSignature);
            } else {
                // Only List and Set are supported for the containerType.
                return null;
            }
        } else {
            signature = toTypeSignature(resolver.elementType());
        }
        final String name = resolver.httpElementName();
        assert name != null;

        final FieldInfoBuilder builder = new FieldInfoBuilder(name, signature)
                .location(location(resolver))
                .requirement(resolver.shouldExist() ? FieldRequirement.REQUIRED
                                                    : FieldRequirement.OPTIONAL);
        if (resolver.description() != null) {
            builder.docString(resolver.description());
        }
        return builder.build();
    }

    @VisibleForTesting
    static TypeSignature toTypeSignature(Type type) {
        requireNonNull(type, "type");

        // The data types defined by the OpenAPI Specification:

        if (type == Void.class || type == void.class) {
            return VOID;
        } else if (type == Boolean.class || type == boolean.class) {
            return BOOLEAN;
        } else if (type == Byte.class || type == byte.class) {
            return BYTE;
        } else if (type == Short.class || type == short.class) {
            return SHORT;
        } else if (type == Integer.class || type == int.class) {
            return INT;
        } else if (type == Long.class || type == long.class) {
            return LONG;
        } else if (type == Float.class || type == float.class) {
            return FLOAT;
        } else if (type == Double.class || type == double.class) {
            return DOUBLE;
        } else if (type == String.class) {
            return STRING;
        } else if (type == byte[].class || type == Byte[].class) {
            return BINARY;
        }
        // End of data types defined by the OpenAPI Specification.

        if (type instanceof ParameterizedType) {
            final ParameterizedType parameterizedType = (ParameterizedType) type;
            final Class<?> rawType = (Class<?>) parameterizedType.getRawType();
            if (List.class.isAssignableFrom(rawType)) {
                return TypeSignature.ofList(toTypeSignature(parameterizedType.getActualTypeArguments()[0]));
            }
            if (Set.class.isAssignableFrom(rawType)) {
                return TypeSignature.ofSet(toTypeSignature(parameterizedType.getActualTypeArguments()[0]));
            }

            if (Map.class.isAssignableFrom(rawType)) {
                final TypeSignature key = toTypeSignature(parameterizedType.getActualTypeArguments()[0]);
                final TypeSignature value = toTypeSignature(parameterizedType.getActualTypeArguments()[1]);
                return TypeSignature.ofMap(key, value);
            }

            final List<TypeSignature> actualTypes = Stream.of(parameterizedType.getActualTypeArguments())
                                                          .map(AnnotatedHttpDocServicePlugin::toTypeSignature)
                                                          .collect(toImmutableList());
            return TypeSignature.ofContainer(rawType.getSimpleName(), actualTypes);
        }

        if (type instanceof WildcardType) {
            // Create an unresolved type with an empty string so that the type name will be '?'.
            return TypeSignature.ofUnresolved("");
        }
        if (type instanceof TypeVariable) {
            return TypeSignature.ofBase(type.getTypeName());
        }
        if (type instanceof GenericArrayType) {
            return TypeSignature.ofList(toTypeSignature(((GenericArrayType) type).getGenericComponentType()));
        }

        if (!(type instanceof Class)) {
            return TypeSignature.ofBase(type.getTypeName());
        }

        final Class<?> clazz = (Class<?>) type;
        if (clazz.isArray()) {
            // If it's an array, return it as a list.
            return TypeSignature.ofList(toTypeSignature(clazz.getComponentType()));
        }

        return TypeSignature.ofBase(clazz.getSimpleName());
    }

    private static FieldLocation location(AnnotatedValueResolver resolver) {
        if (resolver.isPathVariable()) {
            return PATH;
        }
        if (resolver.annotationType() == Param.class) {
            return QUERY;
        }
        if (resolver.annotationType() == Header.class) {
            return HEADER;
        }
        return UNSPECIFIED;
    }

    @VisibleForTesting
    static ServiceSpecification generate(Map<Class<?>, String> serviceDescription,
                                         Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final Set<ServiceInfo> serviceInfos = methodInfos
                .entrySet().stream()
                .map(entry -> {
                    final Class<?> service = entry.getKey();
                    return new ServiceInfo(service.getName(), entry.getValue(),
                                           serviceDescription.get(service));
                })
                .collect(toImmutableSet());

        return ServiceSpecification.generate(serviceInfos, AnnotatedHttpDocServicePlugin::newNamedTypeInfo);
    }

    private static NamedTypeInfo newNamedTypeInfo(TypeSignature typeSignature) {
        final Class<?> type = (Class<?>)
                typeSignature.namedTypeDescriptor()
                             .orElseThrow(
                                     () -> new IllegalArgumentException("cannot create a named type from: " +
                                                                        typeSignature));
        if (type.isEnum()) {
            @SuppressWarnings("unchecked")
            final Class<? extends Enum<?>> enumType = (Class<? extends Enum<?>>) type;
            return new EnumInfo(enumType);
        }

        return newStructInfo(type);
    }

    private static StructInfo newStructInfo(Class<?> structClass) {
        final String name = structClass.getName();

        final Field[] declaredFields = structClass.getDeclaredFields();
        final List<FieldInfo> fields =
                Stream.of(declaredFields)
                      .map(f -> FieldInfo.of(f.getName(), toTypeSignature(f.getGenericType())))
                      .collect(Collectors.toList());
        return new StructInfo(name, fields);
    }

    @Override
    public Set<Class<?>> supportedExampleRequestTypes() {
        return ImmutableSet.of(TreeNode.class);
    }

    @Override
    public Optional<String> serializeExampleRequest(String serviceName, String methodName,
                                                    Object exampleRequest) {
        try {
            return Optional.of(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(exampleRequest));
        } catch (JsonProcessingException e) {
            // Ignore the exception and just return Optional.empty().
        }
        return Optional.empty();
    }
}
