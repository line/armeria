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
import com.linecorp.armeria.xds.validator.XdsValidatorIndex;

/**
 * The default validator which composes pgv (protoc-gen-validate) structural validation with
 * supported-field validation.
 */
@UnstableApi
public final class DefaultXdsValidatorIndex implements XdsValidatorIndex {

    private static final DefaultXdsValidatorIndex INSTANCE = new DefaultXdsValidatorIndex();

    private final PgvValidator pgvValidator = PgvValidator.of();
    private final SupportedFieldValidator supportedFieldValidator = SupportedFieldValidator.of();

    /**
     * Creates a validator that composes pgv and supported-field validation.
     */
    public DefaultXdsValidatorIndex() {}

    /**
     * Returns the default singleton instance.
     */
    public static DefaultXdsValidatorIndex of() {
        return INSTANCE;
    }

    @Override
    public void assertValid(Message message) {
        pgvValidator.assertValid(message);
        supportedFieldValidator.validate((Message) message);
    }
}
