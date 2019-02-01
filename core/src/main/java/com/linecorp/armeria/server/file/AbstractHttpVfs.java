/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.file;

import java.util.List;

import com.google.common.collect.ImmutableList;

/**
 * A skeletal {@link HttpVfs} implementation.
 */
public abstract class AbstractHttpVfs implements HttpVfs {

    @Override
    public boolean canList(String path) {
        return false;
    }

    @Override
    public List<String> list(String path) {
        return ImmutableList.of();
    }

    /**
     * Returns the {@link #meterTag()} of this {@link HttpVfs}.
     */
    @Override
    public String toString() {
        return meterTag();
    }
}
