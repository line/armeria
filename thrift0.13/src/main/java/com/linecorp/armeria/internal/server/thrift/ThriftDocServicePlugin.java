/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.internal.server.thrift;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.apache.thrift.TBase;
import org.apache.thrift.TEnum;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.TFieldRequirementType;
import org.apache.thrift.TSerializer;
import org.apache.thrift.meta_data.EnumMetaData;
import org.apache.thrift.meta_data.FieldMetaData;
import org.apache.thrift.meta_data.FieldValueMetaData;
import org.apache.thrift.meta_data.ListMetaData;
import org.apache.thrift.meta_data.MapMetaData;
import org.apache.thrift.meta_data.SetMetaData;
import org.apache.thrift.meta_data.StructMetaData;
import org.apache.thrift.protocol.TType;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RoutePathType;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.docs.DocServicePlugin;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.EnumInfo;
import com.linecorp.armeria.server.docs.EnumValueInfo;
import com.linecorp.armeria.server.docs.ExceptionInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.NamedTypeInfo;
import com.linecorp.armeria.server.docs.ServiceInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.StructInfo;
import com.linecorp.armeria.server.docs.TypeSignature;
import com.linecorp.armeria.server.thrift.THttpService;

/**
 * {@link DocServicePlugin} implementation that supports {@link THttpService}s.
 */
public final class ThriftDocServicePlugin implements DocServicePlugin {

    private static final String REQUEST_STRUCT_SUFFIX = "_args";

    private static final TypeSignature VOID = TypeSignature.ofBase("void");
    private static final TypeSignature BOOL = TypeSignature.ofBase("bool");
    private static final TypeSignature I8 = TypeSignature.ofBase("i8");
    private static final TypeSignature I16 = TypeSignature.ofBase("i16");
    private static final TypeSignature I32 = TypeSignature.ofBase("i32");
    private static final TypeSignature I64 = TypeSignature.ofBase("i64");
    private static final TypeSignature DOUBLE = TypeSignature.ofBase("double");
    private static final TypeSignature STRING = TypeSignature.ofBase("string");
    private static final TypeSignature BINARY = TypeSignature.ofBase("binary");

    @Nullable
    private static final MethodHandle legacyTSerializerToString;

    static {
        MethodHandle methodHandle = null;
        try {
            methodHandle =
                    MethodHandles.publicLookup()
                                 .findVirtual(TSerializer.class, "toString",
                                              MethodType.methodType(String.class, TBase.class, String.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            // Use TSerializer.toString(TBase) instead.
        }
        legacyTSerializerToString = methodHandle;
    }

    private final ThriftDocStringExtractor docstringExtractor = new ThriftDocStringExtractor();

    // Methods related with generating a service specification.

    @Override
    public String name() {
        return "thrift";
    }

    @Override
    public Set<Class<? extends Service<?, ?>>> supportedServiceTypes() {
        return ImmutableSet.of(THttpService.class);
    }

    @Override
    public ServiceSpecification generateSpecification(Set<ServiceConfig> serviceConfigs,
                                                      DocServiceFilter filter) {
        requireNonNull(serviceConfigs, "serviceConfigs");
        requireNonNull(filter, "filter");

        final Map<Class<?>, EntryBuilder> map = new LinkedHashMap<>();

        for (ServiceConfig c : serviceConfigs) {
            final THttpService service = c.service().as(THttpService.class);
            assert service != null;

            service.entries().forEach((serviceName, entry) -> {
                for (Class<?> iface : entry.interfaces()) {
                    final Class<?> serviceClass = iface.getEnclosingClass();
                    final EntryBuilder builder =
                            map.computeIfAbsent(serviceClass, cls -> new EntryBuilder(serviceClass));

                    // Add all available endpoints. Accept only the services with exact and prefix path
                    // mappings, whose endpoint path can be determined.
                    final Route route = c.route();
                    final RoutePathType pathType = route.pathType();
                    if (pathType == RoutePathType.EXACT || pathType == RoutePathType.PREFIX) {
                        builder.endpoint(
                                EndpointInfo.builder(c.virtualHost().hostnamePattern(), route.paths().get(0))
                                            .fragment(serviceName)
                                            .defaultFormat(service.defaultSerializationFormat())
                                            .availableFormats(service.supportedSerializationFormats())
                                            .build());
                    }
                }
            });
        }

        final List<Entry> entries = map.values().stream()
                                       .map(EntryBuilder::build)
                                       .collect(Collectors.toList());
        return generate(entries, filter);
    }

    @VisibleForTesting
    public ServiceSpecification generate(List<Entry> entries, DocServiceFilter filter) {
        final List<ServiceInfo> services =
                entries.stream()
                       .map(e -> newServiceInfo(e.serviceType, e.endpointInfos, filter))
                       .filter(Objects::nonNull)
                       .collect(toImmutableList());

        return ServiceSpecification.generate(services, ThriftDocServicePlugin::newNamedTypeInfo);
    }

    @VisibleForTesting
    @Nullable
    ServiceInfo newServiceInfo(Class<?> serviceClass, Iterable<EndpointInfo> endpoints,
                               DocServiceFilter filter) {
        requireNonNull(serviceClass, "serviceClass");

        final String name = serviceClass.getName();
        final ClassLoader serviceClassLoader = serviceClass.getClassLoader();
        final String interfaceClassName = name + "$Iface";
        final Class<?> interfaceClass;
        try {
            interfaceClass = Class.forName(interfaceClassName, false, serviceClassLoader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("failed to find a class: " + interfaceClassName, e);
        }
        final Method[] methods = interfaceClass.getDeclaredMethods();

        final List<MethodInfo> methodInfos = Arrays.stream(methods)
                                                   .map(m -> newMethodInfo(m, endpoints, filter))
                                                   .filter(Objects::nonNull)
                                                   .collect(toImmutableList());
        if (methodInfos.isEmpty()) {
            return null;
        }
        return new ServiceInfo(name, methodInfos);
    }

    @Nullable
    private MethodInfo newMethodInfo(Method method, Iterable<EndpointInfo> endpoints,
                                     DocServiceFilter filter) {
        final String methodName = method.getName();

        final Class<?> serviceClass = method.getDeclaringClass().getDeclaringClass();
        final String serviceName = serviceClass.getName();
        if (!filter.test(name(), serviceName, methodName)) {
            return null;
        }
        final ClassLoader classLoader = serviceClass.getClassLoader();

        final String argsClassName = serviceName + '$' + methodName + "_args";
        final Class<? extends TBase<?, ?>> argsClass;
        try {
            @SuppressWarnings("unchecked")
            final Class<? extends TBase<?, ?>> argsClass0 =
                    (Class<? extends TBase<?, ?>>) Class.forName(argsClassName, false, classLoader);
            argsClass = argsClass0;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("failed to find a class: " + argsClassName, e);
        }

        Class<?> resultClass;
        try {
            resultClass = Class.forName(serviceName + '$' + methodName + "_result", false, classLoader);
        } catch (ClassNotFoundException ignored) {
            // Oneway function does not have a result type.
            resultClass = null;
        }

        @SuppressWarnings("unchecked")
        final MethodInfo methodInfo =
                newMethodInfo(methodName, argsClass,
                              (Class<? extends TBase<?, ?>>) resultClass,
                              (Class<? extends TException>[]) method.getExceptionTypes(),
                              endpoints);
        return methodInfo;
    }

    private static MethodInfo newMethodInfo(String name,
                                            Class<? extends TBase<?, ?>> argsClass,
                                            @Nullable Class<? extends TBase<?, ?>> resultClass,
                                            Class<? extends TException>[] exceptionClasses,
                                            Iterable<EndpointInfo> endpoints) {
        requireNonNull(name, "name");
        requireNonNull(argsClass, "argsClass");
        requireNonNull(exceptionClasses, "exceptionClasses");
        requireNonNull(endpoints, "endpoints");

        final List<FieldInfo> parameters =
                FieldMetaData.getStructMetaDataMap(argsClass).values().stream()
                             .map(fieldMetaData -> newFieldInfo(argsClass, fieldMetaData))
                             .collect(toImmutableList());

        // Find the 'success' field.
        FieldInfo fieldInfo = null;
        if (resultClass != null) { // Function isn't "oneway" function
            final Map<? extends TFieldIdEnum, FieldMetaData> resultMetaData =
                    FieldMetaData.getStructMetaDataMap(resultClass);

            for (FieldMetaData fieldMetaData : resultMetaData.values()) {
                if ("success".equals(fieldMetaData.fieldName)) {
                    fieldInfo = newFieldInfo(resultClass, fieldMetaData);
                    break;
                }
            }
        }

        final TypeSignature returnTypeSignature;
        if (fieldInfo == null) {
            returnTypeSignature = VOID;
        } else {
            returnTypeSignature = fieldInfo.typeSignature();
        }

        final List<TypeSignature> exceptionTypeSignatures =
                Arrays.stream(exceptionClasses)
                      .filter(e -> e != TException.class)
                      .map(TypeSignature::ofNamed)
                      .collect(toImmutableList());

        return new MethodInfo(name, returnTypeSignature, parameters, exceptionTypeSignatures, endpoints,
                              ImmutableList.of(),
                              ImmutableList.of(),
                              ImmutableList.of(),
                              ImmutableList.of(),
                              HttpMethod.POST, null);
    }

    private static NamedTypeInfo newNamedTypeInfo(TypeSignature typeSignature) {
        final Class<?> type = (Class<?>) typeSignature.namedTypeDescriptor();
        if (type == null) {
            throw new IllegalArgumentException("cannot create a named type from: " + typeSignature);
        }

        if (type.isEnum()) {
            @SuppressWarnings("unchecked")
            final Class<? extends Enum<? extends TEnum>> enumType =
                    (Class<? extends Enum<? extends TEnum>>) type;
            return newEnumInfo(enumType);
        }

        if (TException.class.isAssignableFrom(type)) {
            @SuppressWarnings("unchecked")
            final Class<? extends TException> castType = (Class<? extends TException>) type;
            return newExceptionInfo(castType);
        }

        assert TBase.class.isAssignableFrom(type);
        @SuppressWarnings("unchecked")
        final Class<? extends TBase<?, ?>> castType = (Class<? extends TBase<?, ?>>) type;
        return newStructInfo(castType);
    }

    @VisibleForTesting
    static StructInfo newStructInfo(Class<? extends TBase<?, ?>> structClass) {
        final String name = structClass.getName();

        final Map<?, FieldMetaData> metaDataMap = FieldMetaData.getStructMetaDataMap(structClass);
        final List<FieldInfo> fields =
                metaDataMap.values().stream()
                           .map(fieldMetaData -> newFieldInfo(structClass, fieldMetaData))
                           .collect(Collectors.toList());

        return new StructInfo(name, fields);
    }

    @VisibleForTesting
    static ExceptionInfo newExceptionInfo(Class<? extends TException> exceptionClass) {
        requireNonNull(exceptionClass, "exceptionClass");
        final String name = exceptionClass.getName();

        List<FieldInfo> fields;
        try {
            @SuppressWarnings("unchecked")
            final Map<?, FieldMetaData> metaDataMap =
                    (Map<?, FieldMetaData>) exceptionClass.getDeclaredField("metaDataMap").get(null);

            fields = metaDataMap.values().stream()
                                .map(fieldMetaData -> newFieldInfo(exceptionClass, fieldMetaData))
                                .collect(toImmutableList());
        } catch (IllegalAccessException e) {
            throw new AssertionError("will not happen", e);
        } catch (NoSuchFieldException ignored) {
            fields = Collections.emptyList();
        }

        return new ExceptionInfo(name, fields);
    }

    @VisibleForTesting
    static FieldInfo newFieldInfo(Class<?> parentType, FieldMetaData fieldMetaData) {
        requireNonNull(fieldMetaData, "fieldMetaData");
        final FieldValueMetaData fieldValueMetaData = fieldMetaData.valueMetaData;
        final TypeSignature typeSignature;

        if (fieldValueMetaData.isStruct() && fieldValueMetaData.isTypedef() &&
            parentType.getSimpleName().equals(fieldValueMetaData.getTypedefName())) {
            // Handle the special case where a struct field refers to itself,
            // where the Thrift compiler handles it as a typedef.
            typeSignature = TypeSignature.ofNamed(parentType);
        } else {
            typeSignature = toTypeSignature(fieldValueMetaData);
        }

        return FieldInfo.builder(fieldMetaData.fieldName, typeSignature)
                        .requirement(convertRequirement(fieldMetaData.requirementType)).build();
    }

    @VisibleForTesting
    static TypeSignature toTypeSignature(FieldValueMetaData fieldValueMetaData) {
        if (fieldValueMetaData instanceof StructMetaData) {
            return TypeSignature.ofNamed(((StructMetaData) fieldValueMetaData).structClass);
        }

        if (fieldValueMetaData instanceof EnumMetaData) {
            return TypeSignature.ofNamed(((EnumMetaData) fieldValueMetaData).enumClass);
        }

        if (fieldValueMetaData instanceof ListMetaData) {
            return TypeSignature.ofList(toTypeSignature(((ListMetaData) fieldValueMetaData).elemMetaData));
        }

        if (fieldValueMetaData instanceof SetMetaData) {
            return TypeSignature.ofSet(toTypeSignature(((SetMetaData) fieldValueMetaData).elemMetaData));
        }

        if (fieldValueMetaData instanceof MapMetaData) {
            return TypeSignature.ofMap(toTypeSignature(((MapMetaData) fieldValueMetaData).keyMetaData),
                                       toTypeSignature(((MapMetaData) fieldValueMetaData).valueMetaData));
        }

        if (fieldValueMetaData.isBinary()) {
            return BINARY;
        }

        switch (fieldValueMetaData.type) {
            case TType.VOID:
                return VOID;
            case TType.BOOL:
                return BOOL;
            case TType.BYTE:
                return I8;
            case TType.DOUBLE:
                return DOUBLE;
            case TType.I16:
                return I16;
            case TType.I32:
                return I32;
            case TType.I64:
                return I64;
            case TType.STRING:
                return STRING;
        }

        final String unresolvedName;
        if (fieldValueMetaData.isTypedef()) {
            unresolvedName = fieldValueMetaData.getTypedefName();
        } else {
            unresolvedName = null;
        }

        return TypeSignature.ofUnresolved(firstNonNull(unresolvedName, "unknown"));
    }

    @VisibleForTesting
    static EnumInfo newEnumInfo(Class<? extends Enum<? extends TEnum>> enumType) {
        final List<EnumValueInfo> values = Arrays.stream(enumType.getEnumConstants())
                                                 .map(e -> new EnumValueInfo(e.name(), ((TEnum) e).getValue()))
                                                 .collect(toImmutableList());

        return new EnumInfo(enumType.getTypeName(), values);
    }

    private static FieldRequirement convertRequirement(byte value) {
        switch (value) {
            case TFieldRequirementType.REQUIRED:
                return FieldRequirement.REQUIRED;
            case TFieldRequirementType.OPTIONAL:
                return FieldRequirement.OPTIONAL;
            case TFieldRequirementType.DEFAULT:
                // Convert to unspecified for consistency with gRPC and AnnotatedService.
                return FieldRequirement.UNSPECIFIED;
            default:
                throw new IllegalArgumentException("unknown requirement type: " + value);
        }
    }

    @VisibleForTesting
    public static final class Entry {
        final Class<?> serviceType;
        final List<EndpointInfo> endpointInfos;

        Entry(Class<?> serviceType, List<EndpointInfo> endpointInfos) {
            this.serviceType = serviceType;
            this.endpointInfos = ImmutableList.copyOf(endpointInfos);
        }
    }

    @VisibleForTesting
    public static final class EntryBuilder {
        private final Class<?> serviceType;
        private final List<EndpointInfo> endpointInfos = new ArrayList<>();

        public EntryBuilder(Class<?> serviceType) {
            this.serviceType = requireNonNull(serviceType, "serviceType");
        }

        public EntryBuilder endpoint(EndpointInfo endpointInfo) {
            endpointInfos.add(requireNonNull(endpointInfo, "endpointInfo"));
            return this;
        }

        public Entry build() {
            return new Entry(serviceType, endpointInfos);
        }
    }

    // Methods related with extracting documentation strings.

    @Override
    public Map<String, String> loadDocStrings(Set<ServiceConfig> serviceConfigs) {
        return serviceConfigs.stream()
                             .flatMap(c -> {
                                 final THttpService service = c.service().as(THttpService.class);
                                 assert service != null;
                                 return service.entries().values().stream();
                             })
                             .flatMap(entry -> entry.interfaces().stream().map(Class::getClassLoader))
                             .flatMap(loader -> docstringExtractor.getAllDocStrings(loader)
                                                                  .entrySet().stream())
                             .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
    }

    // Methods related with serializing example requests.

    @Override
    public Set<Class<?>> supportedExampleRequestTypes() {
        return ImmutableSet.of(TBase.class);
    }

    @Override
    public String guessServiceName(Object exampleRequest) {
        final TBase<?, ?> exampleTBase = asTBase(exampleRequest);
        if (exampleTBase == null) {
            return null;
        }

        return exampleTBase.getClass().getEnclosingClass().getName();
    }

    @Override
    public String guessServiceMethodName(Object exampleRequest) {
        final TBase<?, ?> exampleTBase = asTBase(exampleRequest);
        if (exampleTBase == null) {
            return null;
        }

        final String typeName = exampleTBase.getClass().getName();
        return typeName.substring(typeName.lastIndexOf('$') + 1,
                                  typeName.length() - REQUEST_STRUCT_SUFFIX.length());
    }

    @Override
    public String serializeExampleRequest(String serviceName, String methodName,
                                          Object exampleRequest) {
        if (!(exampleRequest instanceof TBase)) {
            return null;
        }

        final TBase<?, ?> exampleTBase = (TBase<?, ?>) exampleRequest;
        try {
            final TSerializer serializer = new TSerializer(ThriftProtocolFactories.TEXT);
            if (legacyTSerializerToString != null) {
                try {
                    return (String) legacyTSerializerToString.invoke(serializer, exampleTBase,
                                                                     StandardCharsets.UTF_8.name());
                } catch (Throwable ex) {
                    throw new IllegalStateException("Unexpected exception while serializing " + exampleTBase,
                                                    ex);
                }
            } else {
                // TSerializer.toString(TBase, charset) was removed in Thrift 0.14.0
                return serializer.toString(exampleTBase);
            }
        } catch (TException e) {
            throw new Error("should never reach here", e);
        }
    }

    @Nullable
    private static TBase<?, ?> asTBase(Object exampleRequest) {
        final TBase<?, ?> exampleTBase = (TBase<?, ?>) exampleRequest;
        final Class<?> type = exampleTBase.getClass();
        if (!type.getName().endsWith(REQUEST_STRUCT_SUFFIX)) {
            return null;
        }

        final Class<?> serviceType = type.getEnclosingClass();
        if (serviceType == null) {
            return null;
        }

        if (serviceType.getEnclosingClass() != null) {
            return null;
        }

        return exampleTBase;
    }

    @Override
    public String toString() {
        return ThriftDocServicePlugin.class.getSimpleName();
    }
}
