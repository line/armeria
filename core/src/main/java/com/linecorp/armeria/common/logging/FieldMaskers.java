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

package com.linecorp.armeria.common.logging;

import com.linecorp.armeria.common.annotation.Nullable;

final class FieldMaskers {

    static class FallThroughFieldMasker implements FieldMasker {

        static final FieldMasker INSTANCE = new FallThroughFieldMasker();

        @Override
        public Object mask(Object obj) {
            return obj;
        }
    }

    static class NoMaskFieldMasker implements FieldMasker {

        static final FieldMasker INSTANCE = new NoMaskFieldMasker();

        @Override
        public Object mask(Object obj) {
            return obj;
        }
    }

    static class NullifyFieldMasker implements FieldMasker {

        static final FieldMasker INSTANCE = new NullifyFieldMasker();

        @Nullable
        @Override
        public Object mask(Object obj) {
            return null;
        }
    }
}
