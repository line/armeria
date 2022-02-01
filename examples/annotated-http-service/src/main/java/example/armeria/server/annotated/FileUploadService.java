package example.armeria.server.annotated;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.stream.Collectors;

import com.linecorp.armeria.common.AggregatedHttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.multipart.AggregatedMultipart;
import com.linecorp.armeria.common.multipart.Multipart;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;

/**
 * Examples how to use {@link Param} with {@link File}.
 *
 * @see <a href="https://armeria.dev/docs/server-annotated-service#parameter-injection">
 *      Parameter injection</a>
 */
@LoggingDecorator(
        requestLogLevel = LogLevel.INFO,            // Log every request sent to this service at INFO level.
        successfulResponseLogLevel = LogLevel.INFO  // Log every response sent from this service at INFO level.
)
public class FileUploadService {
    @Post("/upload")
    public HttpResponse upload(@Param String text, @Param File file) throws IOException {
        return HttpResponse.ofJson(Arrays.asList(text, Files.readString(file.toPath())));
    }

    @Post("/multipartObject")
    public HttpResponse multipartObject(Multipart multipart) throws IOException {
        return HttpResponse.from(
                multipart.aggregate()
                         .thenApply(AggregatedMultipart::bodyParts)
                         .thenApply(aggregatedBodyParts ->
                                            aggregatedBodyParts.stream()
                                                               .map(AggregatedHttpObject::contentUtf8)
                                                               .collect(Collectors.toList()))
                         .thenApply(HttpResponse::ofJson)
        );
    }
}
