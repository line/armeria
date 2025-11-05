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

package com.linecorp.armeria.internal.server.annotation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.logging.BeanFieldInfo;

final class AnnotatedRequest {

    private final List<@Nullable Object> rawParameters;
    private final List<BeanFieldInfo> beanFieldInfos;

    AnnotatedRequest(Object[] rawParameters, List<BeanFieldInfo> beanFieldInfos) {
        assert rawParameters.length == beanFieldInfos.size();
        this.rawParameters = Collections.unmodifiableList(Arrays.asList(rawParameters));
        this.beanFieldInfos = beanFieldInfos;
    }

    List<@Nullable Object> rawParameters() {
        return rawParameters;
    }

    @Nullable
    Object getParameter(int index) {
        final Object o = rawParameters.get(index);
        return AnnotatedServiceLogUtil.maybeUnwrapFuture(o);
    }

    List<BeanFieldInfo> beanFieldInfos() {
        return beanFieldInfos;
    }

    private List<Object> parameters() {
        final ArrayList<Object> parameters = new ArrayList<>();
        for (int i = 0; i < rawParameters.size(); i++) {
            parameters.add(getParameter(i));
        }
        return parameters;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("parameters", parameters())
                          .toString();
    }
}
