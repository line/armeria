/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.docs;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import org.apache.thrift.meta_data.SetMetaData;
import org.apache.thrift.protocol.TType;

class SetInfo extends TypeInfo implements CollectionInfo {

    static SetInfo of(SetMetaData setMetaData) {
        return of(setMetaData, Collections.emptyMap());
    }

    static SetInfo of(SetMetaData setMetaData, Map<String, String> docStrings) {
        requireNonNull(setMetaData, "setMetaData");

        assert setMetaData.type == TType.SET;
        assert !setMetaData.isBinary();

        return new SetInfo(of(setMetaData.elemMetaData, docStrings));
    }

    static SetInfo of(TypeInfo elementType) {
        return new SetInfo(elementType);
    }

    private final TypeInfo elementType;

    private SetInfo(TypeInfo elementType) {
        super(ValueType.SET, false);

        this.elementType = requireNonNull(elementType, "elementType");
    }

    @Override
    public TypeInfo elementType() {
        return elementType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        if (!super.equals(o)) { return false; }
        SetInfo setInfo = (SetInfo) o;
        return Objects.equals(elementType, setInfo.elementType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), elementType);
    }

    @Override
    public String toString() {
        return "SET<" + elementType + '>';
    }
}
