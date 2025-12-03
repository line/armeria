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

package com.linecorp.armeria.xds.validator;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Validates an xDS resource. Validators are loaded using Java SPI (Service Provider Interface).
 */
@UnstableApi
public interface XdsValidatorIndex {

    /**
     * Validates whether the specified message is valid.
     */
    void assertValid(Object message);

    /**
     * The priority this validator will have. The validator with the highest priority
     * will be selected.
     */
    default int priority() {
        return 0;
    }

    /**
     * A noop validator which doesn't apply any validations.
     */
    static XdsValidatorIndex noop() {
        return message -> {};
    }
}
