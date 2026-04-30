/*
 * Copyright 2025 LY Corporation
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

import java.util.ArrayList;
import java.util.List;

import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.validator.XdsValidationException;
import com.linecorp.armeria.xds.validator.XdsValidatorIndex;

/**
 * A strict {@link XdsValidatorIndex} that composes pgv (protoc-gen-validate) structural validation
 * with supported-field validation. All unsupported field violations are collected and reported
 * together in a single {@link XdsValidationException}.
 *
 * <p>This validator is intended for use in test environments via SPI to catch unsupported
 * field usage early. Its {@link #priority()} is {@code 1}, which is higher than
 * {@link DefaultXdsValidatorIndex} ({@code 0}), so it wins SPI selection when present
 * on the classpath.
 */
@UnstableApi
public final class StrictXdsValidatorIndex implements XdsValidatorIndex {

    private final PgvValidator pgvValidator = PgvValidator.of();

    @Override
    public void assertValid(Message message) {
        requireNonNull(message, "message");
        pgvValidator.assertValid(message);
        final List<String> violations = new ArrayList<>();
        final SupportedFieldValidator validator =
                SupportedFieldValidator.of((descriptorName, fieldPath, value) ->
                        violations.add(descriptorName + ": " + fieldPath));
        validator.validate(message);
        if (!violations.isEmpty()) {
            throw XdsValidationException.of(
                    message, "Unsupported xDS fields detected: " + String.join(", ", violations));
        }
    }

    @Override
    public int priority() {
        return 1;
    }
}
