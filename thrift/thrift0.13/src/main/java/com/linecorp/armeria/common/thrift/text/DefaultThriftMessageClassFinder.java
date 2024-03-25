/*
 * Copyright 2019 LINE Corporation
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
// =================================================================================================
// Copyright 2011 Twitter, Inc.
// -------------------------------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this work except in compliance with the License.
// You may obtain a copy of the License in the LICENSE file, or at:
//
//  https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// =================================================================================================
package com.linecorp.armeria.common.thrift.text;

import com.linecorp.armeria.common.annotation.Nullable;

final class DefaultThriftMessageClassFinder extends AbstractThriftMessageClassFinder {

    @Nullable
    @Override
    public Class<?> get() {
        final StackTraceElement[] frames =
                Thread.currentThread().getStackTrace();

        for (StackTraceElement f : frames) {
            final String className = f.getClassName();
            final Class<?> matchedClazz = getMatchedClass(getClassByName(className));

            if (matchedClazz != null) {
                return matchedClazz;
            }
        }

        return null;
    }
}

