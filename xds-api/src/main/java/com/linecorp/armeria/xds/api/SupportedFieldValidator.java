/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds.api;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Validates protobuf messages against the {@code (armeria.xds.supported)} field annotation.
 * Any set field that lacks the annotation is reported as an unsupported field violation.
 * The validator walks recursively into supported message-typed fields.
 *
 * <p>Currently, supported fields are annotated inline on each field declaration in the proto files, e.g.:
 * <pre>{@code
 * string exact = 1 [(armeria.xds.supported) = true];
 * }</pre>
 */
@UnstableApi
public final class SupportedFieldValidator {

    static final Logger unsupportedLogger = LoggerFactory.getLogger("com.linecorp.armeria.xds.unsupported");
    private static final SupportedFieldValidator DEFAULT = new SupportedFieldValidator(
            UnsupportedFieldHandler.warn());
    private static final SupportedFieldValidator NOOP = new SupportedFieldValidator(
            UnsupportedFieldHandler.ignore());

    private final ConcurrentMap<Descriptors.Descriptor, Set<FieldDescriptor>> supportedFieldsCache =
            new ConcurrentHashMap<>();

    private final UnsupportedFieldHandler handler;

    private SupportedFieldValidator(UnsupportedFieldHandler handler) {
        this.handler = requireNonNull(handler, "handler");
    }

    /**
     * Returns a {@link SupportedFieldValidator} with the default {@link UnsupportedFieldHandler#warn()}
     * handler.
     */
    public static SupportedFieldValidator of() {
        return DEFAULT;
    }

    /**
     * Returns a {@link SupportedFieldValidator} with the specified {@link UnsupportedFieldHandler}.
     */
    public static SupportedFieldValidator of(UnsupportedFieldHandler handler) {
        requireNonNull(handler, "handler");
        if (handler == IgnoreUnsupportedFieldHandler.INSTANCE) {
            return NOOP;
        }
        return new SupportedFieldValidator(handler);
    }

    /**
     * Returns a no-op validator that does not perform any validation.
     */
    public static SupportedFieldValidator noop() {
        return NOOP;
    }

    /**
     * Validates the message, calling the handler directly for each unsupported field found.
     * If the handler is the {@link UnsupportedFieldHandler#ignore()} sentinel, returns immediately
     * to skip recursion cost.
     */
    public void validate(Message message) {
        requireNonNull(message, "message");
        if (handler == IgnoreUnsupportedFieldHandler.INSTANCE) {
            return;
        }
        final String descriptorName = message.getDescriptorForType().getFullName();
        doValidate(message, descriptorName, "$");
    }

    @SuppressWarnings("unchecked")
    private void doValidate(Message message, String descriptorName, String path) {
        if (unsupportedPackage(message.getDescriptorForType().getFile().getPackage())) {
            return;
        }
        final Descriptors.Descriptor descriptor = message.getDescriptorForType();
        final Set<FieldDescriptor> supported = supportedFields(descriptor);

        for (Map.Entry<FieldDescriptor, Object> entry : message.getAllFields().entrySet()) {
            final FieldDescriptor fd = entry.getKey();
            final Object value = entry.getValue();
            final String fieldPath = path + '.' + fd.getJsonName();

            if (!supported.contains(fd)) {
                handler.handle(descriptorName, fieldPath, value);
                continue;
            }

            // Field is supported — check enum values and recurse into nested messages.
            if (fd.getJavaType() == FieldDescriptor.JavaType.ENUM) {
                if (unsupportedPackage(fd.getEnumType().getFile().getPackage())) {
                    continue;
                }
                validateEnumValue(fd, value, descriptorName, fieldPath);
            } else if (fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                if (fd.isMapField()) {
                    final FieldDescriptor valueField = fd.getMessageType().findFieldByNumber(2);
                    if (valueField != null &&
                        valueField.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                        int i = 0;
                        for (Message mapEntry : (Iterable<Message>) value) {
                            final Object mapValue = mapEntry.getField(valueField);
                            if (mapValue instanceof Message) {
                                doValidate((Message) mapValue, descriptorName,
                                           fieldPath + '[' + i + "].value");
                            }
                            i++;
                        }
                    }
                    continue;
                }
                if (fd.isRepeated()) {
                    int i = 0;
                    for (Message element : (Iterable<Message>) value) {
                        doValidate(element, descriptorName, fieldPath + '[' + i + ']');
                        i++;
                    }
                } else {
                    doValidate((Message) value, descriptorName, fieldPath);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validateEnumValue(FieldDescriptor fd, Object value,
                                   String descriptorName, String fieldPath) {
        if (fd.isRepeated()) {
            int i = 0;
            for (EnumValueDescriptor ev : (Iterable<EnumValueDescriptor>) value) {
                if (unsupportedEnumValue(ev)) {
                    handler.handle(descriptorName, fieldPath + '[' + i + ']', ev);
                }
                i++;
            }
        } else {
            final EnumValueDescriptor ev = (EnumValueDescriptor) value;
            if (unsupportedEnumValue(ev)) {
                handler.handle(descriptorName, fieldPath, ev);
            }
        }
    }

    private static boolean unsupportedEnumValue(EnumValueDescriptor ev) {
        return !ev.getOptions().getExtension(SupportedFieldProto.supportedValue);
    }

    private static boolean unsupportedPackage(String pkg) {
        return !(pkg.startsWith("envoy.") || pkg.startsWith("xds.") || pkg.startsWith("armeria."));
    }

    private Set<FieldDescriptor> supportedFields(Descriptors.Descriptor descriptor) {
        return supportedFieldsCache.computeIfAbsent(descriptor, d -> {
            final Set<FieldDescriptor> result = new HashSet<>();
            for (FieldDescriptor fd : d.getFields()) {
                if (fd.getOptions().getExtension(SupportedFieldProto.supported)) {
                    result.add(fd);
                }
            }
            return Collections.unmodifiableSet(result);
        });
    }
}
