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

import static com.google.common.collect.ImmutableMap.toImmutableMap;

import java.io.IOException;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.DocStringExtractor;

/**
 * A DocString extractor for gRPC proto compiled descriptors.
 *
 * <p>To include docstrings in {@link DocService} pages, configure the protobuf compiler to generate
 * a descriptor set with source info and all imports included. Place the descriptor set in the classpath
 * location {@code META-INF/armeria/grpc} and ensure the file extension is one of '.bin', '.desc', '.dsc',
 * '.pb', and '.protobin'. The classpath location
 * can be changed by setting the {@code com.linecorp.armeria.grpc.descriptorDir} system property.
 *
 * <p>For example, to generate a descriptor set in Gradle:
 * <pre>{@code
 * protobuf {
 *     generateProtoTasks {
 *         all().each { task ->
 *             task.generateDescriptorSet = true
 *             task.descriptorSetOptions.includeSourceInfo = true
 *             task.descriptorSetOptions.includeImports = true
 *             task.descriptorSetOptions.path =
 *                     "${buildDir}/resources/main/META-INF/armeria/grpc/service-name.dsc"
 *         }
 *     }
 * }
 * }</pre>
 */
final class GrpcDocStringExtractor extends DocStringExtractor {

    private static final Logger logger = LoggerFactory.getLogger(GrpcDocStringExtractor.class);

    private static final Set<String> acceptableExtensions =
            ImmutableSet.of(".bin", ".desc", ".dsc", ".pb", ".protobin");

    GrpcDocStringExtractor() {
        super("META-INF/armeria/grpc", "com.linecorp.armeria.grpc.descriptorDir");
    }

    @Override
    protected boolean acceptFile(String filename) {
        return acceptableExtensions.stream().anyMatch(filename::endsWith);
    }

    @Override
    protected Map<String, String> getDocStringsFromFiles(Map<String, byte[]> files) {
        return files.entrySet().stream()
                    .flatMap(entry -> {
                        try {
                            final FileDescriptorSet descriptors = FileDescriptorSet.parseFrom(entry.getValue());
                            return descriptors.getFileList().stream();
                        } catch (IOException e) {
                            logger.info("Could not parse file at '{}', skipping. " +
                                        "Is the file a protobuf descriptor file?",
                                        entry.getKey());
                            return Stream.empty();
                        }
                    })
                    .flatMap(f -> parseFile(f).entrySet().stream())
                    .collect(toImmutableMap(Entry::getKey, Entry::getValue, (entry, unused) -> entry));
    }

    private static Map<String, String> parseFile(FileDescriptorProto descriptor) {
        return descriptor.getSourceCodeInfo().getLocationList().stream()
                         .filter(l -> !l.getLeadingComments().isEmpty())
                         .map(l -> {
                             final String fullName = getFullName(descriptor, l.getPathList());
                             if (fullName != null) {
                                 return new SimpleImmutableEntry<>(fullName, l.getLeadingComments());
                             } else {
                                 return null;
                             }
                         })
                         .filter(Objects::nonNull)
                         .collect(toImmutableMap(Entry::getKey, Entry::getValue));
    }

    // A path is field number and indices within a list of types, going through a tree of protobuf
    // descriptors. For example, the 2nd field of the 3rd nested message in the 1st message in a file
    // would have path [MESSAGE_TYPE_FIELD_NUMBER, 0, NESTED_TYPE_FIELD_NUMBER, 2, FIELD_FIELD_NUMBER, 1]
    @Nullable
    private static String getFullName(FileDescriptorProto descriptor, List<Integer> path) {
        String fullNameSoFar = descriptor.getPackage();
        switch (path.get(0)) {
            case FileDescriptorProto.MESSAGE_TYPE_FIELD_NUMBER:
                final DescriptorProto message = descriptor.getMessageType(path.get(1));
                return appendMessageToFullName(message, path, fullNameSoFar);
            case FileDescriptorProto.ENUM_TYPE_FIELD_NUMBER:
                final EnumDescriptorProto enumDescriptor = descriptor.getEnumType(path.get(1));
                return appendEnumToFullName(enumDescriptor, path, fullNameSoFar);
            case FileDescriptorProto.SERVICE_FIELD_NUMBER:
                final ServiceDescriptorProto serviceDescriptor = descriptor.getService(path.get(1));
                fullNameSoFar = appendNameComponent(fullNameSoFar, serviceDescriptor.getName());
                if (path.size() > 2) {
                    fullNameSoFar = appendMethodToFullName(serviceDescriptor, path, fullNameSoFar);
                }
                return fullNameSoFar;
            default:
                return null;
        }
    }

    @Nullable
    private static String appendMethodToFullName(ServiceDescriptorProto serviceDescriptorProto,
                                                 List<Integer> path, String fullNameSoFar) {
        if (path.size() == 4 && path.get(2) == ServiceDescriptorProto.METHOD_FIELD_NUMBER) {
            return appendFieldComponent(fullNameSoFar,
                                        serviceDescriptorProto.getMethod(path.get(3)).getName());
        }
        return null;
    }

    @Nullable
    private static String appendToFullName(
            DescriptorProto messageDescriptor, List<Integer> path, String fullNameSoFar) {
        switch (path.get(0)) {
            case DescriptorProto.NESTED_TYPE_FIELD_NUMBER:
                final DescriptorProto nestedMessage = messageDescriptor.getNestedType(path.get(1));
                return appendMessageToFullName(nestedMessage, path, fullNameSoFar);
            case DescriptorProto.ENUM_TYPE_FIELD_NUMBER:
                final EnumDescriptorProto enumDescriptor = messageDescriptor.getEnumType(path.get(1));
                return appendEnumToFullName(enumDescriptor, path, fullNameSoFar);
            case DescriptorProto.FIELD_FIELD_NUMBER:
                final FieldDescriptorProto fieldDescriptor = messageDescriptor.getField(path.get(1));
                return appendFieldComponent(fullNameSoFar, fieldDescriptor.getName());
            default:
                return null;
        }
    }

    @Nullable
    private static String appendMessageToFullName(
            DescriptorProto message, List<Integer> path, String fullNameSoFar) {
        fullNameSoFar = appendNameComponent(fullNameSoFar, message.getName());
        return path.size() > 2 ? appendToFullName(message, path.subList(2, path.size()), fullNameSoFar)
                               : fullNameSoFar;
    }

    @Nullable
    private static String appendEnumToFullName(
            EnumDescriptorProto enumDescriptor, List<Integer> path, String fullNameSoFar) {
        fullNameSoFar = appendNameComponent(fullNameSoFar, enumDescriptor.getName());
        if (path.size() <= 2) {
            return fullNameSoFar;
        }
        if (path.get(2) == EnumDescriptorProto.VALUE_FIELD_NUMBER) {
            return appendFieldComponent(fullNameSoFar, enumDescriptor.getValue(path.get(3)).getName());
        }
        return null;
    }

    private static String appendNameComponent(String nameSoFar, String component) {
        return nameSoFar + '.' + component;
    }

    private static String appendFieldComponent(String nameSoFar, String component) {
        return nameSoFar + '/' + component;
    }
}
