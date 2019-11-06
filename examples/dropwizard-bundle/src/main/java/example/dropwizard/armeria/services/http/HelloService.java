package example.dropwizard.armeria.services.http;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Produces;

public class HelloService {

    @Get("/hello")
    @Produces("text/plain")
    public HttpResponse helloText() {
        // Return a text document to the client.
        return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Armeria");
    }

    @Get("/hello")
    @Produces("application/json")
    public HttpResponse helloJson() {
        // Return a JSON object to the client.
        return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{ \"name\": \"Armeria\" }");
    }
}
