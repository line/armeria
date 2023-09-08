/*
 * Copyright 2015 LINE Corporation
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

import java.util.Iterator;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A parsing context used for Sequences (lists & sets). Maintains its
 * child elements, and a pointer to the current one being parsed.
 *
 * @author Alex Roetter
 */
final class SequenceContext extends BaseContext {

    @Nullable
    private final Iterator<JsonNode> children;
    @Nullable
    private JsonNode currentChild;

    /**
     * Create an iterator over the children. May be constructed with a null
     * JsonArray if we only use it for writing.
     */
    SequenceContext(@Nullable JsonNode json) {
        children = null != json ? json.elements() : null;
    }

    @Override
    protected void read() {
        assert children != null;
        if (!children.hasNext()) {
            throw new RuntimeException(
                    "Called SequenceContext.read() too many times!");
        }
        currentChild = children.next();
    }

    @Override
    protected JsonNode getCurrentChild() {
        assert currentChild != null;
        return currentChild;
    }

    @Override
    protected boolean hasMoreChildren() {
        assert children != null;
        return children.hasNext();
    }
}
