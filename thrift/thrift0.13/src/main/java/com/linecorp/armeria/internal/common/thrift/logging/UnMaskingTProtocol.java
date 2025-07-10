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

package com.linecorp.armeria.internal.common.thrift.logging;

import java.nio.ByteBuffer;

import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TField;
import org.apache.thrift.protocol.TList;
import org.apache.thrift.protocol.TMap;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TSet;
import org.apache.thrift.protocol.TStruct;

class UnMaskingTProtocol extends AbstractUnMaskingTProtocol {

    UnMaskingTProtocol(TProtocol delegate, TBase<?, ?> base, TBaseSelectorCache selectorCache) {
        super(delegate, base, selectorCache);
    }

    @Override
    public void writeMessageBegin(TMessage message) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeMessageEnd() throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeStructBegin(TStruct struct) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeStructEnd() throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeFieldBegin(TField field) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeFieldEnd() throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeFieldStop() throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeMapBegin(TMap map) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeMapEnd() throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeListBegin(TList list) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeListEnd() throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeSetBegin(TSet set) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeSetEnd() throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeBool(boolean b) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeByte(byte b) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeI16(short i16) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeI32(int i32) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeI64(long i64) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeDouble(double dub) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeString(String str) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeBinary(ByteBuffer buf) throws TException {
        throw new UnsupportedOperationException();
    }
}
