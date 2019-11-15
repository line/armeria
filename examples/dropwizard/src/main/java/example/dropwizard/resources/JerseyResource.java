package example.dropwizard.resources;

import javax.ws.rs.GET;
import javax.ws.rs.Path;

@Path("/jersey")
public class JerseyResource {
    @GET
    public String get() {
        return "Hello, Jersey!";
    }
}
