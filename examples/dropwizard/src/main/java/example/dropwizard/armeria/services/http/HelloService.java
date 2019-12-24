package example.dropwizard.armeria.services.http;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.ProducesText;

public class HelloService {

    @Get("/hello")
    @ProducesText
    public String helloText() {
        // Return a text document to the client.
        return "Armeria";
    }

    @Get("/hello")
    @ProducesJson
    public HttpResponse helloJson() {
        // Return a JSON object to the client.
        return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{ \"name\": \"Armeria\" }");
    }
}
