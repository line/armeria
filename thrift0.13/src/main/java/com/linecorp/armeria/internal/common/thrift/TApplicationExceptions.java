/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.internal.common.thrift;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TField;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolUtil;
import org.apache.thrift.protocol.TType;

import com.linecorp.armeria.common.annotation.Nullable;

public final class TApplicationExceptions {

    /**
     * Reads a {@link TApplicationException} from the specified {@link TProtocol}.
     *
     * <p>Note: This has been copied from {@link TApplicationException#read(TProtocol)} due to API differences
     * between libthrift 0.9.x and 0.10.x.
     */
    public static TApplicationException read(TProtocol iprot) throws TException {
        TField field;
        iprot.readStructBegin();

        @Nullable String message = null;
        int type = TApplicationException.UNKNOWN;

        while (true) {
            field = iprot.readFieldBegin();
            if (field.type == TType.STOP) {
                break;
            }
            switch (field.id) {
                case 1:
                    if (field.type == TType.STRING) {
                        message = iprot.readString();
                    } else {
                        TProtocolUtil.skip(iprot, field.type);
                    }
                    break;
                case 2:
                    if (field.type == TType.I32) {
                        type = iprot.readI32();
                    } else {
                        TProtocolUtil.skip(iprot, field.type);
                    }
                    break;
                default:
                    TProtocolUtil.skip(iprot, field.type);
                    break;
            }
            iprot.readFieldEnd();
        }
        iprot.readStructEnd();

        return new TApplicationException(type, message);
    }

    private TApplicationExceptions() {}
}
