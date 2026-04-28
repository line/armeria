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

import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.validator.XdsValidationException;

import io.envoyproxy.pgv.ReflectiveValidatorIndex;
import io.envoyproxy.pgv.ValidationException;
import io.envoyproxy.pgv.ValidatorIndex;

/**
 * Validates xDS protobuf messages using pgv (protoc-gen-validate) structural validation.
 */
@UnstableApi
public final class PgvValidator {

    private static final PgvValidator INSTANCE = new PgvValidator();

    private final ValidatorIndex delegate = new ReflectiveValidatorIndex();

    private PgvValidator() {}

    /**
     * Returns the singleton {@link PgvValidator} instance.
     */
    public static PgvValidator of() {
        return INSTANCE;
    }

    /**
     * Validates the given message using pgv structural validation.
     *
     * @throws XdsValidationException if validation fails
     */
    public void assertValid(Message message) {
        try {
            delegate.validatorFor(message).assertValid(message);
        } catch (ValidationException e) {
            throw XdsValidationException.of(message, e);
        }
    }
}
