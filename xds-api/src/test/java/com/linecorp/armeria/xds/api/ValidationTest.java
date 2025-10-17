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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.pgv.ReflectiveValidatorIndex;
import io.envoyproxy.pgv.ValidationException;
import io.envoyproxy.pgv.ValidatorIndex;

class ValidationTest {

    @Test
    void validationIsEnabled() throws Exception {
        final ValidatorIndex index = new ReflectiveValidatorIndex();
        final VirtualHost virtualHost = VirtualHost.getDefaultInstance();
        assertThatThrownBy(() -> index.validatorFor(VirtualHost.class).assertValid(virtualHost))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("length must be at least 1 but got: 0");
    }
}
