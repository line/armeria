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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.internal.server.thrift.ThriftDescriptiveTypeInfoProvider.VOID;
import static com.linecorp.armeria.internal.server.thrift.ThriftDescriptiveTypeInfoProvider.newFieldInfo;
import static java.util.Objects.requireNonNull;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.thrift.TBase;
import org.apache.thrift.TEnum;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.TSerializer;
import org.apache.thrift.meta_data.FieldMetaData;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.internal.common.thrift.ThriftMetadataAccess;
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
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.ServiceInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.TypeSignature;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.server.thrift.ThriftServiceEntry;

/**
 * {@link DocServicePlugin} implementation that supports {@link THttpService}s.
 */
public final class ThriftDocServicePlugin implements DocServicePlugin {

    private static final String REQUEST_STRUCT_SUFFIX = "_args";

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
                                                      DocServiceFilter filter,
                                                      DescriptiveTypeInfoProvider descriptiveTypeInfoProvider) {
        requireNonNull(serviceConfigs, "serviceConfigs");
        requireNonNull(filter, "filter");
        requireNonNull(descriptiveTypeInfoProvider, "descriptiveTypeInfoProvider");

        final Map<Class<?>, EntryBuilder> map = new LinkedHashMap<>();

        for (ServiceConfig c : serviceConfigs) {
            final THttpService service = c.service().as(THttpService.class);
            assert service != null;

            service.entries().forEach((serviceName, entry) -> {
                for (Class<?> iface : entry.interfaces()) {
                    final Class<?> serviceClass = iface.getEnclosingClass();
                    final EntryBuilder builder =
                            map.computeIfAbsent(serviceClass, cls -> new EntryBuilder(serviceClass, entry));
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
        return generate(entries, filter, descriptiveTypeInfoProvider);
    }

    @VisibleForTesting
    public ServiceSpecification generate(List<Entry> entries, DocServiceFilter filter,
                                         DescriptiveTypeInfoProvider descriptiveTypeInfoProvider) {
        final List<ServiceInfo> services =
                entries.stream()
                       .map(e -> newServiceInfo(e, filter))
                       .filter(Objects::nonNull)
                       .collect(toImmutableList());

        return ServiceSpecification.generate(
                services, typeSignature -> newDescriptiveTypeInfo(typeSignature, descriptiveTypeInfoProvider));
    }

    @VisibleForTesting
    @Nullable
    ServiceInfo newServiceInfo(Entry entry, DocServiceFilter filter) {
        final Class<?> serviceClass = entry.serviceType;
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
                                                   .map(m -> newMethodInfo(m, entry, filter))
                                                   .filter(Objects::nonNull)
                                                   .collect(toImmutableList());
        if (methodInfos.isEmpty()) {
            return null;
        }
        return new ServiceInfo(name, methodInfos);
    }

    @Nullable
    private MethodInfo newMethodInfo(Method method, Entry entry,
                                     DocServiceFilter filter) {
        // Get the function name in thrift IDL
        final String thriftFunctionName;
        if (entry.thriftServiceEntry != null) {
            thriftFunctionName = entry.thriftServiceEntry.functionName(method.getName());
        } else {
            // entry.thriftServiceEntry can be null for tests
            thriftFunctionName = method.getName();
        }

        final Class<?> serviceClass = method.getDeclaringClass().getDeclaringClass();
        final String serviceName = serviceClass.getName();
        if (!filter.test(name(), serviceName, thriftFunctionName)) {
            return null;
        }
        final ClassLoader classLoader = serviceClass.getClassLoader();

        final String argsClassName = serviceName + '$' + thriftFunctionName + "_args";
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
            resultClass = Class.forName(serviceName + '$' + thriftFunctionName + "_result", false, classLoader);
        } catch (ClassNotFoundException ignored) {
            // Oneway function does not have a result type.
            resultClass = null;
        }

        @SuppressWarnings("unchecked")
        final MethodInfo methodInfo =
                newMethodInfo(serviceName, thriftFunctionName, argsClass,
                              (Class<? extends TBase<?, ?>>) resultClass,
                              (Class<? extends TException>[]) method.getExceptionTypes(),
                              entry.endpointInfos);
        return methodInfo;
    }

    private static <T extends TBase<T, F>, F extends TFieldIdEnum> MethodInfo newMethodInfo(
            String serviceName, String name,
            Class<? extends TBase<?, ?>> argsClass,
            @Nullable Class<? extends TBase<?, ?>> resultClass,
            Class<? extends TException>[] exceptionClasses,
            Iterable<EndpointInfo> endpoints) {
        requireNonNull(serviceName, "serviceName");
        requireNonNull(name, "name");
        requireNonNull(argsClass, "argsClass");
        requireNonNull(exceptionClasses, "exceptionClasses");
        requireNonNull(endpoints, "endpoints");

        final List<FieldInfo> parameters =
                ThriftMetadataAccess.getStructMetaDataMap(argsClass).values().stream()
                                    .map(fieldMetaData -> newFieldInfo(argsClass, fieldMetaData))
                                    .collect(toImmutableList());

        // Find the 'success' field.
        FieldInfo fieldInfo = null;
        if (resultClass != null) { // Function isn't "oneway" function
            final Map<?, FieldMetaData> resultMetaData =
                    ThriftMetadataAccess.getStructMetaDataMap(resultClass);

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
                      .map(TypeSignature::ofStruct)
                      .collect(toImmutableList());

        return new MethodInfo(serviceName, name, returnTypeSignature, parameters, false,
                              exceptionTypeSignatures,
                              endpoints,
                              ImmutableList.of(),
                              ImmutableList.of(),
                              ImmutableList.of(),
                              ImmutableList.of(), HttpMethod.POST, DescriptionInfo.empty());
    }

    private static DescriptiveTypeInfo newDescriptiveTypeInfo(
            DescriptiveTypeSignature typeSignature,
            DescriptiveTypeInfoProvider descriptiveTypeInfoProvider) {
        final Class<?> type = (Class<?>) typeSignature.descriptor();
        assert TBase.class.isAssignableFrom(type) ||
               TEnum.class.isAssignableFrom(type) ||
               TException.class.isAssignableFrom(type);

        final DescriptiveTypeInfo descriptiveTypeInfo =
                descriptiveTypeInfoProvider.newDescriptiveTypeInfo(type);
        return requireNonNull(descriptiveTypeInfo,
                              "descriptiveTypeInfoProvider.newDescriptiveTypeInfo() returned null");
    }

    @VisibleForTesting
    static final class Entry {
        final Class<?> serviceType;
        final List<EndpointInfo> endpointInfos;
        @Nullable
        final ThriftServiceEntry thriftServiceEntry;

        Entry(Class<?> serviceType, List<EndpointInfo> endpointInfos,
              @Nullable ThriftServiceEntry thriftServiceEntry) {
            this.serviceType = serviceType;
            this.endpointInfos = ImmutableList.copyOf(endpointInfos);
            this.thriftServiceEntry = thriftServiceEntry;
        }
    }

    @VisibleForTesting
    static final class EntryBuilder {
        private final Class<?> serviceType;
        private final List<EndpointInfo> endpointInfos = new ArrayList<>();
        @Nullable
        private final ThriftServiceEntry thriftServiceEntry;

        EntryBuilder(Class<?> serviceType) {
            this(serviceType, null);
        }

        EntryBuilder(Class<?> serviceType, @Nullable ThriftServiceEntry thriftServiceEntry) {
            this.serviceType = requireNonNull(serviceType, "serviceType");
            this.thriftServiceEntry = thriftServiceEntry;
        }

        EntryBuilder endpoint(EndpointInfo endpointInfo) {
            endpointInfos.add(requireNonNull(endpointInfo, "endpointInfo"));
            return this;
        }

        Entry build() {
            return new Entry(serviceType, endpointInfos, thriftServiceEntry);
        }
    }

    @Override
    public Map<String, DescriptionInfo> loadDocStrings(Set<ServiceConfig> serviceConfigs) {
        return serviceConfigs.stream()
                             .flatMap(c -> {
                                 final THttpService service = c.service().as(THttpService.class);
                                 assert service != null;
                                 return service.entries().values().stream();
                             })
                             .flatMap(entry -> entry.interfaces().stream().map(Class::getClassLoader))
                             .flatMap(loader -> docstringExtractor.getAllDocStrings(loader)
                                                                  .entrySet().stream())
                             .collect(toImmutableMap(Map.Entry<String, String>::getKey,
                                                     (Map.Entry<String, String> entry) ->
                                                             DescriptionInfo.of(entry.getValue()),
                                                     (a, b) -> a));
    }

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
            final TSerializer serializer = new TSerializer(ThriftProtocolFactories.text());
            if (legacyTSerializerToString != null) {
                try {
                    return (String) legacyTSerializerToString.invoke(serializer, exampleTBase,
                                                                     StandardCharsets.UTF_8.name());
                } catch (Throwable ex) {
                    throw new IllegalStateException(
                            "Unexpected exception while serializing " + exampleTBase, ex);
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
