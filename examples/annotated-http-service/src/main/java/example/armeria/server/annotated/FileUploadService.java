package example.armeria.server.annotated;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.stream.Collectors;

import com.linecorp.armeria.common.AggregatedHttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.multipart.AggregatedMultipart;
import com.linecorp.armeria.common.multipart.Multipart;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;

/**
 * Examples how to use {@link Param} with {@link File}.
 */
@LoggingDecorator(
        requestLogLevel = LogLevel.INFO,            // Log every request sent to this service at INFO level.
        successfulResponseLogLevel = LogLevel.INFO  // Log every response sent from this service at INFO level.
)
@Consumes("multipart/form-data")
public class FileUploadService {
    @Post("/upload")
    public HttpResponse upload(@Param String text, @Param File file) throws IOException {
        return HttpResponse.from(() -> {
            try {
                final String content = Files.readString(file.toPath());
                return HttpResponse.ofJson(Arrays.asList(text, content));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                file.delete();
            }
        }, ServiceRequestContext.current().blockingTaskExecutor().withoutContext());
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
