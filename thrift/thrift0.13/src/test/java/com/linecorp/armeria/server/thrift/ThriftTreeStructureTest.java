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

package com.linecorp.armeria.server.thrift;

import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static org.assertj.core.api.Assertions.assertThat;

import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.thrift.tree.Branch;
import testing.thrift.tree.IntLeaf;
import testing.thrift.tree.LeafType;
import testing.thrift.tree.StringLeaf;
import testing.thrift.tree.TreeRequest;
import testing.thrift.tree.TreeService;

class ThriftTreeStructureTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/tree", THttpService.of(new TreeServiceImpl()));
        }
    };

    private static TreeRequest treeRequest;

    @BeforeEach
    void setUp() {
        final LeafType intLeaf1 = LeafType.intLeaf(new IntLeaf(1));
        final LeafType intLeaf2 = LeafType.intLeaf(new IntLeaf(2));
        final LeafType intLeaf3 = LeafType.intLeaf(new IntLeaf(3));
        final LeafType stringLeaf1 = LeafType.stringLeaf(new StringLeaf("a"));
        final LeafType stringLeaf2 = LeafType.stringLeaf(new StringLeaf("b"));
        final LeafType stringLeaf3 = LeafType.stringLeaf(new StringLeaf("c"));
        final Branch branch = new Branch().setLeafTypes(
                ImmutableList.of(intLeaf1, stringLeaf1, LeafType.branch(new Branch().setLeafTypes(
                        ImmutableList.of(intLeaf2, stringLeaf2, LeafType.branch(new Branch().setLeafTypes(
                                ImmutableList.of(intLeaf3, stringLeaf3))))))));
        final LeafType base = LeafType.branch(branch);
        treeRequest = new TreeRequest().setBase(base);
    }

    @Test
    void testRecursiveUnionCodec() throws TException {
        for (SerializationFormat format : ThriftSerializationFormats.values()) {
            final TreeService.Iface client = ThriftClients.newClient(server.uri(HTTP, format).resolve("/tree"),
                                                                     TreeService.Iface.class);
            assertThat(client.createTree(treeRequest)).isEqualTo("OK");
        }
    }

    private static class TreeServiceImpl implements TreeService.AsyncIface {
        @SuppressWarnings("unchecked")
        @Override
        public void createTree(TreeRequest request, AsyncMethodCallback resultHandler)
                throws TException {
            assertThat(request).isEqualTo(treeRequest);
            resultHandler.onComplete("OK");
        }
    }
}
