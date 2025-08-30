package example.armeria.server.animal;

import static java.util.Objects.requireNonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.docs.DocService;

public final class PolymorphismDocServiceExample {

    private static final Logger logger = LoggerFactory.getLogger(PolymorphismDocServiceExample.class);

    // --- Data Transfer Objects (DTOs) from the test case ---

    public static void main(String[] args) throws Exception {
        final Server server = Server.builder().http(8080).annotatedService("/api", new AnimalService())
                                    .serviceUnder("/docs", new DocService()) // Add DocService
                                    .build();

        server.start().join();

        logger.info("Server has been started. You can view the documentation at:");
        logger.info("UI: http://127.0.0.1:8080/docs/");
        logger.info("JSON Specification: http://127.0.0.1:8080/docs/specification.json");
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "species")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Dog.class, name = "dog"),
            @JsonSubTypes.Type(value = Cat.class, name = "cat")
    })
    interface Animal {
        String name();
    }

    abstract static class Mammal implements Animal {
        @JsonProperty
        private final String name;

        protected Mammal(String name) {
            this.name = requireNonNull(name, "name");
        }

        @Override
        public String name() {
            return name;
        }

        public abstract String sound();
    }

    static final class Toy {
        @JsonProperty
        private final String toyName;
        @JsonProperty
        private final String color;

        @JsonCreator
        Toy(@JsonProperty("toyName") String toyName, @JsonProperty("color") String color) {
            this.toyName = requireNonNull(toyName, "toyName");
            this.color = requireNonNull(color, "color");
        }

        public String toyName() {
            return toyName;
        }

        public String color() {
            return color;
        }
    }

    static final class Dog extends Mammal {
        @JsonProperty
        private final int age;
        @JsonProperty
        private final String[] favoriteFoods;
        @JsonProperty
        private final Toy favoriteToy;

        @JsonCreator
        Dog(@JsonProperty("name") String name, @JsonProperty("age") int age,
            @JsonProperty("favoriteFoods") String[] favoriteFoods,
            @JsonProperty("favoriteToy") Toy favoriteToy) {
            super(name);
            this.age = age;
            this.favoriteFoods = requireNonNull(favoriteFoods, "favoriteFoods");
            this.favoriteToy = requireNonNull(favoriteToy, "favoriteToy");
        }

        @Override
        public String sound() {
            return "woof";
        }

        public int age() {
            return age;
        }

        public String[] favoriteFoods() {
            return favoriteFoods;
        }

        public Toy favoriteToy() {
            return favoriteToy;
        }
    }

    static final class Cat extends Mammal {
        @JsonProperty
        private final boolean likesTuna;
        @JsonProperty
        private final Toy scratchPost;

        @JsonCreator
        Cat(@JsonProperty("name") String name, @JsonProperty("likesTuna") boolean likesTuna,
            @JsonProperty("scratchPost") Toy scratchPost) {
            super(name);
            this.likesTuna = likesTuna;
            this.scratchPost = requireNonNull(scratchPost, "scratchPost");
        }

        @Override
        public String sound() {
            return "meow";
        }

        public boolean likesTuna() {
            return likesTuna;
        }

        public Toy scratchPost() {
            return scratchPost;
        }
    }

    // --- Annotated Service ---
    public static class AnimalService {
        @Post("/animal")
        public String processAnimal(Animal animal) {
            // This method uses the polymorphic Animal interface.
            // DocService will analyze this and generate documentation for the complex types.
            String response = "Received animal named: " + animal.name();
            if (animal instanceof Mammal) {
                response += ". It says: " + ((Mammal) animal).sound();
            }
            logger.info(response);
            return response;
        }
    }
}
