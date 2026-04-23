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
import java.util.List;
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
 * Validates protobuf messages against the {@code (armeria.xds.supported_fields)} message-level annotation.
 * Any set field whose number is not listed in the message's {@code supported_fields} option is reported
 * as an unsupported field violation. The validator walks recursively into supported message-typed fields.
 *
 * <p>Supported fields are annotated at the message level in the proto files, e.g.:
 * <pre>{@code
 * message StringMatcher {
 *   option (armeria.xds.supported_fields) = 1;
 *   string exact = 1;
 * }
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
            if (fd.isMapField()) {
                final FieldDescriptor valueField = fd.getMessageType().findFieldByNumber(2);
                if (valueField != null) {
                    final List<Message> mapEntries = (List<Message>) value;
                    for (int i = 0; i < mapEntries.size(); i++) {
                        validateFieldValue(valueField, mapEntries.get(i).getField(valueField),
                                           descriptorName, fieldPath + '[' + i + "].value");
                    }
                }
            } else if (fd.isRepeated()) {
                final List<?> elements = (List<?>) value;
                for (int i = 0; i < elements.size(); i++) {
                    validateFieldValue(fd, elements.get(i), descriptorName,
                                       fieldPath + '[' + i + ']');
                }
            } else {
                validateFieldValue(fd, value, descriptorName, fieldPath);
            }
        }
    }

    private void validateFieldValue(FieldDescriptor fd, Object value,
                                    String descriptorName, String fieldPath) {
        if (fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
            if (value instanceof Message) {
                doValidate((Message) value, descriptorName, fieldPath);
            }
        } else if (fd.getJavaType() == FieldDescriptor.JavaType.ENUM) {
            if (!unsupportedPackage(fd.getEnumType().getFile().getPackage()) &&
                value instanceof EnumValueDescriptor) {
                final EnumValueDescriptor ev = (EnumValueDescriptor) value;
                if (unsupportedEnumValue(ev)) {
                    handler.handle(descriptorName, fieldPath, ev);
                }
            }
        }
    }

    private static boolean unsupportedEnumValue(EnumValueDescriptor ev) {
        final List<Integer> supportedValues =
                ev.getType().getOptions().getExtension(SupportedFieldProto.supported.enumValue);
        return !supportedValues.contains(ev.getNumber());
    }

    private static boolean unsupportedPackage(String pkg) {
        return !(pkg.startsWith("envoy.") || pkg.startsWith("xds.") || pkg.startsWith("armeria."));
    }

    private Set<FieldDescriptor> supportedFields(Descriptors.Descriptor descriptor) {
        return supportedFieldsCache.computeIfAbsent(descriptor, d -> {
            final List<Integer> supportedNumbers =
                    d.getOptions().getExtension(SupportedFieldProto.supported.field);
            final Set<FieldDescriptor> result = new HashSet<>();
            for (FieldDescriptor fd : d.getFields()) {
                if (supportedNumbers.contains(fd.getNumber())) {
                    result.add(fd);
                }
            }
            return Collections.unmodifiableSet(result);
        });
    }
}
