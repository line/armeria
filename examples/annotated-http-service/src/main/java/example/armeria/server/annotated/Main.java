/*
 * Copyright 2022 LINE Corporation
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

package example.armeria.server.annotated;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.docs.DocService;

public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        final Server server = newServer(8080);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop().join();
            logger.info("Server has been stopped.");
        }));

        server.start().join();

        logger.info("Server has been started. Serving DocService at http://127.0.0.1:{}/docs",
                    server.activeLocalPort());
    }

    /**
     * Returns a new {@link Server} instance configured with annotated HTTP services.
     *
     * @param port the port that the server is to be bound to
     */
    private static Server newServer(int port) {
        final ServerBuilder sb = Server.builder();
        sb.http(port);
        configureServices(sb);
        return sb.build();
    }

    static void configureServices(ServerBuilder sb) {
        sb.annotatedService("/pathPattern", new PathPatternService())
          .annotatedService("/injection", new InjectionService())
          .annotatedService("/file", new FileUploadService())
          .annotatedService("/messageConverter", new MessageConverterService())
          .annotatedService("/exception", new ExceptionHandlerService())
          .serviceUnder("/docs",
                        DocService.builder()
                                  .examplePaths(PathPatternService.class,
                                                "pathsVar",
                                                "/pathPattern/paths/first/foo",
                                                "/pathPattern/paths/second/bar")
                                  .examplePaths(PathPatternService.class,
                                                "pathVar",
                                                "/pathPattern/path/foo",
                                                "/pathPattern/path/bar")
                                  .exampleRequests(MessageConverterService.class,
                                                   "json1",
                                                   "{\"name\":\"bar\"}"
                                  )
                                  .build());
    }
}
