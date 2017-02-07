/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.server.thrift;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.apache.thrift.TBase;
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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.docs.ClassInfo;
import com.linecorp.armeria.server.docs.CollectionInfo;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.EnumInfo;
import com.linecorp.armeria.server.docs.ExceptionInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.FunctionInfo;
import com.linecorp.armeria.server.docs.ListInfo;
import com.linecorp.armeria.server.docs.MapInfo;
import com.linecorp.armeria.server.docs.ServiceInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.ServiceSpecificationGenerator;
import com.linecorp.armeria.server.docs.SetInfo;
import com.linecorp.armeria.server.docs.StructInfo;
import com.linecorp.armeria.server.docs.TypeInfo;

/**
 * {@link ServiceSpecificationGenerator} implementation that supports {@link THttpService}s.
 */
public class ThriftServiceSpecificationGenerator implements ServiceSpecificationGenerator {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Set<Class<? extends Service<?, ?>>> supportedServiceTypes() {
        return ImmutableSet.of(THttpService.class);
    }

    @Override
    public ServiceSpecification generate(Iterable<ServiceConfig> serviceConfigs) {
        final Map<Class<?>, Iterable<EndpointInfo>> map = new LinkedHashMap<>();

        for (ServiceConfig c : serviceConfigs) {
            final THttpService service = c.service().as(THttpService.class).get();
            service.entries().forEach((serviceName, entry) -> {
                for (Class<?> iface : entry.interfaces()) {
                    final Class<?> serviceClass = iface.getEnclosingClass();
                    final List<EndpointInfo> endpoints =
                            (List<EndpointInfo>) map.computeIfAbsent(serviceClass, cls -> new ArrayList<>());

                    c.pathMapping().exactPath().ifPresent(
                            p -> endpoints.add(new EndpointInfo(
                                    c.virtualHost().hostnamePattern(),
                                    p, serviceName,
                                    service.defaultSerializationFormat(),
                                    service.allowedSerializationFormats())));
                }
            });
        }

        return generate(map);
    }

    @VisibleForTesting
    static ServiceSpecification generate(Map<Class<?>, Iterable<EndpointInfo>> map) {
        final List<ServiceInfo> services = new ArrayList<>(map.size());
        final Set<ClassInfo> classes = new HashSet<>();
        map.forEach((serviceClass, endpoints) -> {
            try {
                // FIXME(trustin): Bring sampleRequests and sampleHttpHeaders back.
                final ServiceInfo service = newServiceInfo(
                        serviceClass, endpoints, Collections.emptyMap(), Collections.emptyMap());
                services.add(service);
                classes.addAll(service.classes().values());
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("unable to initialize Specification", e);
            }
        });

        return new ServiceSpecification(services, classes);
    }

    @VisibleForTesting
    static ServiceInfo newServiceInfo(
            Class<?> serviceClass, Iterable<EndpointInfo> endpoints,
            Map<Class<?>, ? extends TBase<?, ?>> sampleRequests,
            Map<String, String> sampleHttpHeaders) throws ClassNotFoundException {
        requireNonNull(serviceClass, "serviceClass");

        final String name = serviceClass.getName();

        final ClassLoader serviceClassLoader = serviceClass.getClassLoader();
        final Class<?> interfaceClass = Class.forName(name + "$Iface", false, serviceClassLoader);
        final Method[] methods = interfaceClass.getDeclaredMethods();
        final Map<String, String> docStrings = ThriftDocString.getAllDocStrings(serviceClassLoader);

        final List<FunctionInfo> functions = new ArrayList<>(methods.length);
        final Set<ClassInfo> classes = new LinkedHashSet<>();
        for (Method method : methods) {
            final FunctionInfo function = newFunctionInfo(method, sampleRequests, name, docStrings);
            functions.add(function);

            addClassIfPossible(classes, function.returnTypeInfo());
            function.parameters().forEach(p -> addClassIfPossible(classes, p.typeInfo()));
            function.exceptions().forEach(e -> {
                e.fields().forEach(f -> addClassIfPossible(classes, f.typeInfo()));
                addClassIfPossible(classes, e);
            });
        }

        String httpHeaders = "";
        if (sampleHttpHeaders != null) {
            try {
                httpHeaders = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sampleHttpHeaders);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Failed to serialize the given httpHeaders", e);
            }
        }

        return new ServiceInfo(name, functions, classes, endpoints, docStrings.get(name), httpHeaders);
    }

    private static void addClassIfPossible(Set<ClassInfo> classes, TypeInfo typeInfo) {
        if (typeInfo instanceof ClassInfo) {
            final ClassInfo classInfo = (ClassInfo) typeInfo;
            classInfo.fields().forEach(f -> addClassIfPossible(classes, f.typeInfo()));
            classes.add(classInfo);
        } else if (typeInfo instanceof CollectionInfo) {
            addClassIfPossible(classes, ((CollectionInfo) typeInfo).elementTypeInfo());
        } else if (typeInfo instanceof MapInfo) {
            final MapInfo mapInfo = (MapInfo) typeInfo;
            addClassIfPossible(classes, mapInfo.keyTypeInfo());
            addClassIfPossible(classes, mapInfo.valueTypeInfo());
        }
    }

    private static FunctionInfo newFunctionInfo(Method method,
                                                Map<Class<?>, ? extends TBase<?, ?>> sampleRequests,
                                                @Nullable String namespace,
                                                Map<String, String> docStrings) throws ClassNotFoundException {
        requireNonNull(method, "method");

        final String methodName = method.getName();

        final Class<?> serviceClass = method.getDeclaringClass().getDeclaringClass();
        final String serviceName = serviceClass.getName();
        final ClassLoader classLoader = serviceClass.getClassLoader();

        @SuppressWarnings("unchecked")
        Class<? extends TBase<?, ?>> argsClass = (Class<? extends TBase<?, ?>>) Class.forName(
                serviceName + '$' + methodName + "_args", false, classLoader);
        String sampleJsonRequest;
        TBase<?, ?> sampleRequest = sampleRequests.get(argsClass);
        if (sampleRequest == null) {
            sampleJsonRequest = "";
        } else {
            TSerializer serializer = new TSerializer(ThriftProtocolFactories.TEXT);
            try {
                sampleJsonRequest = serializer.toString(sampleRequest, StandardCharsets.UTF_8.name());
            } catch (TException e) {
                throw new IllegalArgumentException(
                        "Failed to serialize to a memory buffer, this shouldn't ever happen.", e);
            }
        }

        Class<?> resultClass;
        try {
            resultClass =  Class.forName(serviceName + '$' + methodName + "_result", false, classLoader);
        } catch (ClassNotFoundException ignored) {
            // Oneway function does not have a result type.
            resultClass = null;
        }

        @SuppressWarnings("unchecked")
        final FunctionInfo function =
                newFunctionInfo(namespace,
                                methodName,
                                argsClass,
                                (Class<? extends TBase<?, ?>>) resultClass,
                                (Class<? extends TException>[]) method.getExceptionTypes(),
                                sampleJsonRequest,
                                docStrings);
        return function;
    }


    private static FunctionInfo newFunctionInfo(String namespace,
                                                String name,
                                                Class<? extends TBase<?, ?>> argsClass,
                                                @Nullable Class<? extends TBase<?, ?>> resultClass,
                                                Class<? extends TException>[] exceptionClasses,
                                                String sampleJsonRequest,
                                                Map<String, String> docStrings) {
        requireNonNull(name, "name");
        final String functionNamespace = ThriftDocString.key(namespace, name);
        final String docString = docStrings.get(functionNamespace);
        requireNonNull(argsClass, "argsClass");
        requireNonNull(exceptionClasses, "exceptionClasses");

        final List<FieldInfo> parameters =
                FieldMetaData.getStructMetaDataMap(argsClass).values().stream()
                             .map(fieldMetaData -> newFieldInfo(fieldMetaData, functionNamespace, docStrings))
                             .collect(toImmutableList());

        // Find the 'success' field.
        FieldInfo fieldInfo = null;
        if (resultClass != null) { // Function isn't "oneway" function
            final Map<? extends TFieldIdEnum, FieldMetaData> resultMetaData =
                    FieldMetaData.getStructMetaDataMap(resultClass);

            for (FieldMetaData fieldMetaData : resultMetaData.values()) {
                if ("success".equals(fieldMetaData.fieldName)) {
                    fieldInfo = newFieldInfo(fieldMetaData, functionNamespace, docStrings);
                    break;
                }
            }
        }

        final TypeInfo returnType;
        if (fieldInfo == null) {
            returnType = TypeInfo.VOID;
        } else {
            returnType = fieldInfo.typeInfo();
        }

        final List<ExceptionInfo> exceptions =
                Arrays.stream(exceptionClasses)
                      .filter(e -> e != TException.class)
                      .map(e -> newExceptionInfo(e, docStrings))
                      .collect(toImmutableList());

        return new FunctionInfo(name, returnType, parameters, exceptions, sampleJsonRequest, docString);
    }

    @VisibleForTesting
    static FieldInfo newFieldInfo(FieldMetaData fieldMetaData, @Nullable String namespace,
                                          Map<String, String> docStrings) {
        requireNonNull(fieldMetaData, "fieldMetaData");
        final String docStringKey = ThriftDocString.key(namespace, fieldMetaData.fieldName);
        return new FieldInfo(fieldMetaData.fieldName,
                             convertRequirement(fieldMetaData.requirementType),
                             newTypeInfo(fieldMetaData.valueMetaData, docStrings),
                             docStrings.get(docStringKey));
    }

    private static TypeInfo newTypeInfo(FieldValueMetaData fieldValueMetaData, Map<String, String> docStrings) {
        if (fieldValueMetaData instanceof StructMetaData) {
            return newStructInfo((StructMetaData) fieldValueMetaData, docStrings);
        }

        if (fieldValueMetaData instanceof EnumMetaData) {
            return newEnumInfo((EnumMetaData) fieldValueMetaData, docStrings);
        }

        if (fieldValueMetaData instanceof ListMetaData) {
            return newListInfo((ListMetaData) fieldValueMetaData, docStrings);
        }

        if (fieldValueMetaData instanceof SetMetaData) {
            return newSetInfo((SetMetaData) fieldValueMetaData, docStrings);
        }

        if (fieldValueMetaData instanceof MapMetaData) {
            return newMapInfo((MapMetaData) fieldValueMetaData, docStrings);
        }

        final TypeInfo typeInfo;
        if (fieldValueMetaData.isBinary()) {
            typeInfo = TypeInfo.BINARY;
        } else {
            switch (fieldValueMetaData.type) {
                case TType.VOID:
                    typeInfo = TypeInfo.VOID;
                    break;
                case TType.BOOL:
                    typeInfo = TypeInfo.BOOL;
                    break;
                case TType.BYTE:
                    typeInfo = TypeInfo.I8;
                    break;
                case TType.DOUBLE:
                    typeInfo = TypeInfo.DOUBLE;
                    break;
                case TType.I16:
                    typeInfo = TypeInfo.I16;
                    break;
                case TType.I32:
                    typeInfo = TypeInfo.I32;
                    break;
                case TType.I64:
                    typeInfo = TypeInfo.I64;
                    break;
                case TType.STRING:
                    typeInfo = TypeInfo.STRING;
                    break;
                default:
                    throw new IllegalArgumentException(
                            "unexpected field value type: " + fieldValueMetaData.type);
            }
        }

        return typeInfo;
    }

    @VisibleForTesting
    static StructInfo newStructInfo(StructMetaData structMetaData, Map<String, String> docStrings) {
        final Class<?> structClass = structMetaData.structClass;
        final String name = structClass.getName();

        assert structMetaData.type == TType.STRUCT;
        assert !structMetaData.isBinary();

        final Map<?, FieldMetaData> metaDataMap =
                FieldMetaData.getStructMetaDataMap(structMetaData.structClass);
        final List<FieldInfo> fields =
                metaDataMap.values().stream()
                           .map(fieldMetaData -> newFieldInfo(fieldMetaData, name, docStrings))
                           .collect(Collectors.toList());

        return new StructInfo(name, fields, docStrings.get(name));
    }

    @VisibleForTesting
    static EnumInfo newEnumInfo(EnumMetaData enumMetaData, Map<String, String> docStrings) {
        requireNonNull(enumMetaData, "enumMetaData");

        final Class<?> enumClass = enumMetaData.enumClass;

        assert enumMetaData.type == TType.ENUM;
        assert !enumMetaData.isBinary();

        final List<Object> constants = new ArrayList<>();
        final Field[] fields = enumClass.getDeclaredFields();
        for (Field field : fields) {
            if (field.isEnumConstant()) {
                try {
                    constants.add(field.get(null));
                } catch (IllegalAccessException ignored) {
                    // Skip inaccessible fields.
                }
            }
        }

        final String name = enumClass.getName();
        return new EnumInfo(name, constants, docStrings.get(name));
    }

    @VisibleForTesting
    static ListInfo newListInfo(ListMetaData listMetaData, Map<String, String> docStrings) {
        requireNonNull(listMetaData, "listMetaData");

        assert listMetaData.type == TType.LIST;
        assert !listMetaData.isBinary();

        return new ListInfo(newTypeInfo(listMetaData.elemMetaData, docStrings));
    }

    @VisibleForTesting
    static SetInfo newSetInfo(SetMetaData setMetaData, Map<String, String> docStrings) {
        requireNonNull(setMetaData, "setMetaData");

        assert setMetaData.type == TType.SET;
        assert !setMetaData.isBinary();

        return new SetInfo(newTypeInfo(setMetaData.elemMetaData, docStrings));
    }

    @VisibleForTesting
    static MapInfo newMapInfo(MapMetaData mapMetaData, Map<String, String> docStrings) {
        requireNonNull(mapMetaData, "mapMetaData");

        assert mapMetaData.type == TType.MAP;
        assert !mapMetaData.isBinary();

        return new MapInfo(newTypeInfo(mapMetaData.keyMetaData, docStrings),
                           newTypeInfo(mapMetaData.valueMetaData, docStrings));
    }

    @VisibleForTesting
    static ExceptionInfo newExceptionInfo(Class<? extends TException> exceptionClass,
                                          Map<String, String> docStrings) {

        requireNonNull(exceptionClass, "exceptionClass");
        final String name = exceptionClass.getName();

        List<FieldInfo> fields;
        try {
            @SuppressWarnings("unchecked")
            final Map<?, FieldMetaData> metaDataMap =
                    (Map<?, FieldMetaData>) exceptionClass.getDeclaredField("metaDataMap").get(null);

            fields = metaDataMap.values().stream()
                                .map(fieldMetaData -> newFieldInfo(fieldMetaData, name, docStrings))
                                .collect(toImmutableList());
        } catch (IllegalAccessException e) {
            throw new AssertionError("will not happen", e);
        } catch (NoSuchFieldException ignored) {
            fields = Collections.emptyList();
        }

        return new ExceptionInfo(name, fields, docStrings.get(name));
    }

    private static FieldRequirement convertRequirement(byte value) {
        switch (value) {
            case TFieldRequirementType.REQUIRED:
                return FieldRequirement.REQUIRED;
            case TFieldRequirementType.OPTIONAL:
                return FieldRequirement.OPTIONAL;
            case TFieldRequirementType.DEFAULT:
                return FieldRequirement.DEFAULT;
            default:
                throw new IllegalArgumentException("unknown requirement type: " + value);
        }
    }
}
