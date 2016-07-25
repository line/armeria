// =================================================================================================
// Copyright 2011 Twitter, Inc.
// -------------------------------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this work except in compliance with the License.
// You may obtain a copy of the License in the LICENSE file, or at:
//
//  http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// =================================================================================================

package com.linecorp.armeria.common.thrift.text;

import javax.annotation.Nullable;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TField;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A base parsing context. Used as a root level parsing context for
 * parsing Json Objects
 *
 * @author Alex Roetter
 */
class BaseContext {

    /**
     * Complain about a method called on a BaseContext that shouldn't have been.
     */
    private static <T> T unsupportedOperation() {
        throw new UnsupportedOperationException("Not supported by BaseContext.");
    }

    /**
     * Called before we write an item
     */
    protected void write() {
    }

    /**
     * Called before we read an item.
     */
    protected void read() {
    }

    /**
     * Thrift maps are made up of name value pairs, are we parsing a
     * thrift map name (e.g. left hand side of a map entry) here?
     */
    protected boolean isMapKey() {
        return false;
    }

    /**
     * Return the TField struct describing a Thrift struct item with
     * the given name.
     */
    protected TField getTFieldByName(String name) throws TException {
        return unsupportedOperation();
    }

    /**
     * Returns the Java class for the field name if it is an enum or a struct,
     * or null otherwise.
     */
    @Nullable
    protected Class<?> getClassByFieldName(String fieldName) {
        return null;
    }

    /**
     * Return the json element that should be processed next. Used for
     * Contexts that have child JsonElements, e.g. Sequences, Maps, etc.
     */
    protected JsonNode getCurrentChild() {
        return unsupportedOperation();
    }

    /**
     * Are there more child elements to process?
     */
    protected boolean hasMoreChildren() {
        return (Boolean) unsupportedOperation();
    }
}
