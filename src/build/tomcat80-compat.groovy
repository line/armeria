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

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

import java.nio.file.Files
import java.nio.file.Paths

// This script removes the methods that did not exist until Tomcat 8.5 from the ProtocolHandler implementation
// using bytecode manipulation. We need the methods to exist during compilation time since we compile the
// ProtocolHandler implementation against Tomcat 8.5, but want to make sure the methods do not exist because
// otherwise JVM will fail to load the ProtocolHandler due to the references to the non-existent classes in the
// method signatures.

path = Paths.get("target", "classes", "com", "linecorp", "armeria", "server", "http", "tomcat",
                 "Tomcat80ProtocolHandler.class")

reader = new ClassReader(Files.readAllBytes(path))
writer = new ClassWriter(ClassWriter.COMPUTE_MAXS)

reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
    @Override
    MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        if (name == "addSslHostConfig" || name == "findSslHostConfigs" ||
            name == "addUpgradeProtocol" || name == "findUpgradeProtocols") {
            return null
        } else {
            return super.visitMethod(access, name, desc, signature, exceptions)
        }
    }
}, 0)

Files.write(path, writer.toByteArray())
