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
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A map parsing context that tracks if we are parsing a key, which
 * is on the left hand side of the ":" operator, or a value.
 * Json mandates that keys are strings
 * e.g.
 * {
 * "1" : 1,
 * "2" : 2,
 * }
 * Note the required quotes on the lhs.
 * We maintain an iterator over all of our child name/value pairs, and
 * a pointer to the current one being parsed.
 *
 * @author Alex Roetter
 */
class PairContext extends BaseContext {

    @Nullable
    private final Iterator<Map.Entry<String, JsonNode>> children;
    private boolean lhs;
    @Nullable
    private Map.Entry<String, JsonNode> currentChild;

    /**
     * Creates an iterator over this object's children.
     */
    protected PairContext(@Nullable JsonNode json) {
        children = null != json ? json.fields() : null;
    }

    @Override
    protected final void write() {
        lhs = !lhs;
    }

    @Override
    protected final void read() {
        lhs = !lhs;
        // every other time, do a read, since the read gets the name & value
        // at once.
        if (isLhs()) {
            assert children != null;
            if (!children.hasNext()) {
                throw new RuntimeException(
                        "Called PairContext.read() too many times!");
            }
            currentChild = children.next();
        }
    }

    @Override
    protected final JsonNode getCurrentChild() {
        assert currentChild != null;
        if (lhs) {
            return new TextNode(currentChild.getKey());
        }
        return currentChild.getValue();
    }

    @Override
    protected final boolean hasMoreChildren() {
        assert children != null;
        return children.hasNext();
    }

    protected final boolean isLhs() {
        return lhs;
    }
}
