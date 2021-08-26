/*
 * Copyright 2021 LINE Corporation
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
 * under the License
 */
/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linecorp.armeria.server.management;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.management.PlatformManagedObject;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.CompletableHttpResponse;
import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.file.HttpFile;

enum HeapDumpService implements HttpService {

    // Forked from https://github.com/spring-projects/spring-boot/blob/531ee83c6ae42d8027a5241faf42c2015b1a031c/spring-boot-project/spring-boot-actuator/src/main/java/org/springframework/boot/actuate/management/HeapDumpWebEndpoint.java

    INSTANCE;

    private static final Logger logger = LoggerFactory.getLogger(HeapDumpService.class);

    private static final Executor heapDumpExecutor = Executors.newSingleThreadExecutor(
            ThreadFactories.newThreadFactory("armeria-heapdump-executor", true));

    @Nullable
    private HeapDumper heapDumper;

    @Nullable
    private Throwable unavailabilityCause;

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        if (unavailabilityCause != null) {
            return HttpResponse.ofFailure(unavailabilityCause);
        }

        final CompletableHttpResponse response = HttpResponse.defer();
        heapDumpExecutor.execute(() -> {
            if (ctx.isCancelled()) {
                return;
            }

            if (heapDumper == null) {
                try {
                    heapDumper = new HeapDumper();
                } catch (Throwable ex) {
                    unavailabilityCause = ex;
                    response.complete(HttpResponse.ofFailure(ex));
                    return;
                }
            }

            File tempFile = null;
            try {
                final QueryParams queryParams = QueryParams.fromQueryString(ctx.query());
                final boolean live = queryParams.contains("live", "true");
                final String date = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm").format(LocalDateTime.now());
                final String fileName = "heapdump_pid" + SystemInfo.pid() + '_' + date + (live ? "_live" : "");

                tempFile = createTempFile(fileName);
                heapDumper.dumpHeap(tempFile, live);

                final ContentDisposition disposition = ContentDisposition.builder("attachment")
                                                                         .filename(fileName + ".hprof")
                                                                         .build();
                final HttpFile httpFile = HttpFile.builder(tempFile)
                                                  .addHeader(HttpHeaderNames.CONTENT_DISPOSITION, disposition)
                                                  .build();

                final File heapDumpFile = tempFile;
                final HttpResponse httpResponse = httpFile.asService().serve(ctx, req);
                response.complete(httpResponse);
                httpResponse.whenComplete().handleAsync((unused1, unused2) -> {
                    deleteTempFile(heapDumpFile);
                    return null;
                }, heapDumpExecutor);
            } catch (Throwable cause) {
                logger.warn("Unexpected exception while creating a heap dump", cause);
                if (tempFile != null) {
                    deleteTempFile(tempFile);
                }
                response.complete(HttpResponse.ofFailure(cause));
            }
        });

        return response;
    }

    private static File createTempFile(String fileName) throws IOException {
        final File file = File.createTempFile(fileName, ".hprof");
        file.delete();
        return file;
    }

    private static void deleteTempFile(File file) {
        try {
            Files.delete(file.toPath());
        } catch (IOException ex) {
            logger.warn("Failed to delete temporary heap dump file '" + file + '\'', ex);
        }
    }

    /**
     * A {@link HeapDumper} that uses {@code com.sun.management.HotSpotDiagnosticMXBean}
     * available on Oracle and OpenJDK to dump the heap to a file.
     */
    private static class HeapDumper {

        private final Object diagnosticMXBean;
        private final MethodHandle dumpHeapMH;

        HeapDumper() {
            try {
                final Class<?> diagnosticMXBeanClass =
                        Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
                //noinspection unchecked
                diagnosticMXBean = ManagementFactory
                        .getPlatformMXBean((Class<PlatformManagedObject>) diagnosticMXBeanClass);
                final MethodType methodType = MethodType.methodType(void.class, String.class, boolean.class);
                dumpHeapMH = MethodHandles.publicLookup()
                                          .findVirtual(diagnosticMXBeanClass, "dumpHeap", methodType);
            } catch (Throwable ex) {
                throw new IllegalStateException("Unable to locate HotSpotDiagnosticMXBean", ex);
            }
        }

        /**
         * Dumps the current heap to the specified {@link File}.
         * @param file the file to dump the heap to
         * @param live if only {@code live} objects (i.e. objects that are reachable from others) should be
         *             dumped
         */
        void dumpHeap(File file, boolean live) throws Throwable {
            dumpHeapMH.invoke(diagnosticMXBean, file.getAbsolutePath(), live);
        }
    }
}
