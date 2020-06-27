/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.common;

import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;

/**
 * A {@link ReferenceCounted} {@link HttpData} whose content is always empty.
 * Used when testing if an empty reference-counted data is released by the callee.
 */
final class EmptyReferenceCountedHttpData
        extends AbstractReferenceCounted
        implements HttpData {

    @Override
    public byte[] array() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int length() {
        return 0;
    }

    @Override
    public HttpData withEndOfStream(boolean endOfStream) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isEndOfStream() {
        return false;
    }

    @Override
    protected void deallocate() {}

    @Override
    public ReferenceCounted touch(Object hint) {
        return this;
    }
}
