/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.internal.server.annotation;

import static java.util.Objects.requireNonNull;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.testing.TestUtil;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.TypeSignature;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class PolymorphismDocServiceTest {

    private static final Logger logger = LoggerFactory.getLogger(PolymorphismDocServiceTest.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String vetRecordJson =
            "{\"vaccinationHistory\":{\"Rabies\":\"2025-01-01\", \"FeLV\":\"2025-02-01\"}}";

    private static final String dogExampleRequest =
            "{\"species\":\"dog\", \"name\":\"Buddy\", \"age\":5, \"favoriteFoods\":[\"beef\"]," +
            "\"favoriteToy\":{\"toyName\":\"ball\", \"color\":\"red\"}," +
            "\"vetRecord\":" + vetRecordJson + '}';

    private static final String catExampleRequest =
            "{\"species\":\"cat\", \"name\":\"Lucy\", \"likesTuna\":true," +
            "\"scratchPost\":{\"toyName\":\"tower\", \"color\":\"beige\"}," +
            "\"vetRecord\":" + vetRecordJson + '}';

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            if (TestUtil.isDocServiceDemoMode()) {
                sb.http(8081);
            }
            sb.annotatedService("/api", new AnimalService());
            sb.serviceUnder("/docs",
                            DocService.builder()
                                      .exampleRequests(AnimalService.class, "processAnimal",
                                                       dogExampleRequest, catExampleRequest)
                                      .build());
        }
    };

    @Test
    void specificationShouldBeGeneratedCorrectly() throws Exception {

        if (TestUtil.isDocServiceDemoMode()) {
            Thread.sleep(Long.MAX_VALUE);
        }
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/docs/specification.json").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);

        final String specificationJson = res.contentUtf8();
        final JsonNode specNode = mapper.readTree(specificationJson);

        final JsonNode structsNode = specNode.path("structs");
        final String animalClassName = TypeSignature.ofStruct(Animal.class).name();
        final String vetRecordClassName = TypeSignature.ofStruct(VetRecord.class).name();
        final String dogClassName = TypeSignature.ofStruct(Dog.class).name();
        final String catClassName = TypeSignature.ofStruct(Cat.class).name();

        boolean animalStructFound = false;
        boolean vetRecordStructFound = false;

        for (final JsonNode struct : structsNode) {
            final String currentName = struct.path("name").asText();
            if (animalClassName.equals(currentName)) {
                animalStructFound = true;
                final JsonNode oneOfNode = struct.path("oneOf");
                assertThat(oneOfNode.isArray()).isTrue();
                final List<String> oneOfList = new ArrayList<>();
                oneOfNode.forEach(node -> oneOfList.add(node.asText()));
                assertThat(oneOfList).containsExactlyInAnyOrder(dogClassName, catClassName);
                assertThatJson(struct).node("discriminator.propertyName").isStringEqualTo("species");
            }
            if (vetRecordClassName.equals(currentName)) {
                vetRecordStructFound = true;
            }
        }

        assertThat(animalStructFound).as("Animal struct with polymorphism info not found").isTrue();
        assertThat(vetRecordStructFound).as("VetRecord struct (for MAP test) not found").isTrue();

        final JsonNode methodsNode = specNode.path("services").get(0).path("methods");
        boolean apiResponseMethodFound = false;
        for (final JsonNode method : methodsNode) {
            if ("getExampleResponse".equals(method.path("name").asText())) {
                apiResponseMethodFound = true;
                final String expectedReturnType = TypeSignature.ofContainer(
                        ApiResponse.class.getSimpleName(),
                        ImmutableList.of(TypeSignature.ofStruct(Toy.class))
                ).signature();

                assertThatJson(method).node("returnTypeSignature").isStringEqualTo(expectedReturnType);
                break;
            }
        }
        assertThat(apiResponseMethodFound).as("getExampleResponse method for ApiResponse test not found")
                                          .isTrue();
    }

    @Test
    void shouldDeserializePolymorphicObject() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .addHeader("Content-Type", MediaType.JSON_UTF_8.toString())
                                          .build();

        final AggregatedHttpResponse responseForDog = client.post("/api/animal", dogExampleRequest).aggregate()
                                                            .join();
        assertThat(responseForDog.status()).isEqualTo(HttpStatus.OK);
        assertThat(responseForDog.contentUtf8()).contains("woof");

        final AggregatedHttpResponse responseForCat = client.post("/api/animal", catExampleRequest).aggregate()
                                                            .join();
        assertThat(responseForCat.status()).isEqualTo(HttpStatus.OK);
        assertThat(responseForCat.contentUtf8()).contains("meow");
    }

    @Test
    void shouldDeserializeNestedPolymorphicList() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .addHeader("Content-Type", MediaType.JSON_UTF_8.toString())
                                          .build();
        final String zooRequest = "{\"animals\": [" + dogExampleRequest + ',' + catExampleRequest + "]}";
        final AggregatedHttpResponse response = client.post("/api/zoo", zooRequest).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("Received 2 animals");
    }

    @Test
    void shouldDeserializeMapAndContainerTypes() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .addHeader("Content-Type", MediaType.JSON_UTF_8.toString())
                                          .build();

        final AggregatedHttpResponse responseForRecord = client.post("/api/animal/record", dogExampleRequest)
                                                               .aggregate().join();
        assertThat(responseForRecord.status()).isEqualTo(HttpStatus.OK);
        assertThat(responseForRecord.contentUtf8()).contains("Rabies", "FeLV");

        final AggregatedHttpResponse responseForOptional = client.post("/api/animal/optional",
                                                                       dogExampleRequest).aggregate().join();
        assertThat(responseForOptional.status()).isEqualTo(HttpStatus.OK);
        assertThat(responseForOptional.contentUtf8()).isEqualTo("Received optional animal: Buddy");
    }

    @Test
    void specificationForEmptySubTypes() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/docs/specification.json").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);

        final String specificationJson = res.contentUtf8();
        final JsonNode specNode = mapper.readTree(specificationJson);

        final String misconfiguredClassName = TypeSignature.ofStruct(MisconfiguredAnimal.class).name();
        boolean structFound = false;
        for (final JsonNode struct : specNode.path("structs")) {
            if (misconfiguredClassName.equals(struct.path("name").asText())) {
                structFound = true;
                assertThatJson(struct).node("oneOf").isAbsent();
                assertThatJson(struct).node("discriminator").isAbsent();
                assertThatJson(struct).node("fields").isArray().isEmpty();
                break;
            }
        }
        assertThat(structFound).as("MisconfiguredAnimal struct should exist and be simple").isTrue();
    }

    // --- DTOs and Service for the test ---

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
    }

    static final class VetRecord {
        @JsonProperty
        private final Map<String, String> vaccinationHistory;

        @JsonCreator
        VetRecord(@JsonProperty("vaccinationHistory") Map<String, String> history) {
            this.vaccinationHistory = ImmutableMap.copyOf(requireNonNull(history, "history"));
        }

        public Map<String, String> vaccinationHistory() {
            return vaccinationHistory;
        }
    }

    static final class Dog extends Mammal {
        @JsonProperty
        private final int age;
        @JsonProperty
        private final String[] favoriteFoods;
        @JsonProperty
        private final Toy favoriteToy;
        @JsonProperty
        private final VetRecord vetRecord;

        @JsonCreator
        Dog(@JsonProperty("name") String name, @JsonProperty("age") int age,
            @JsonProperty("favoriteFoods") String[] favoriteFoods, @JsonProperty("favoriteToy") Toy toy,
            @JsonProperty("vetRecord") VetRecord vetRecord) {
            super(name);
            this.age = age;
            this.favoriteFoods = requireNonNull(favoriteFoods, "favoriteFoods");
            this.favoriteToy = requireNonNull(toy, "favoriteToy");
            this.vetRecord = requireNonNull(vetRecord, "vetRecord");
        }

        @Override
        public String sound() {
            return "woof";
        }

        public VetRecord vetRecord() {
            return vetRecord;
        }
    }

    static final class Cat extends Mammal {
        @JsonProperty
        private final boolean likesTuna;
        @JsonProperty
        private final Toy scratchPost;
        @JsonProperty
        private final VetRecord vetRecord;

        @JsonCreator
        Cat(@JsonProperty("name") String name, @JsonProperty("likesTuna") boolean likesTuna,
            @JsonProperty("scratchPost") Toy scratchPost, @JsonProperty("vetRecord") VetRecord vetRecord) {
            super(name);
            this.likesTuna = likesTuna;
            this.scratchPost = requireNonNull(scratchPost, "scratchPost");
            this.vetRecord = requireNonNull(vetRecord, "vetRecord");
        }

        @Override
        public String sound() {
            return "meow";
        }

        public VetRecord vetRecord() {
            return vetRecord;
        }
    }

    static class Zoo {
        @JsonProperty
        private final List<Animal> animals;

        @JsonCreator
        Zoo(@JsonProperty("animals") List<Animal> animals) {
            this.animals = ImmutableList.copyOf(requireNonNull(animals, "animals"));
        }
    }

    static final class ApiResponse<T> {
        @JsonProperty
        private final int status;
        @JsonProperty
        private final T data;

        ApiResponse(int status, T data) {
            this.status = status;
            this.data = data;
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({})
    interface MisconfiguredAnimal {
        String name();
    }

    static final class Inventory {
        @JsonProperty
        private final Map<String, Integer> consumableCounts;
        @JsonProperty
        private final Map<String, Toy> equipment;

        @JsonCreator
        Inventory(@JsonProperty("consumableCounts") Map<String, Integer> consumableCounts,
                  @JsonProperty("equipment") Map<String, Toy> equipment) {
            this.consumableCounts = requireNonNull(consumableCounts, "consumableCounts");
            this.equipment = requireNonNull(equipment, "equipment");
        }
    }

    public static class AnimalService {

        @Post("/animal")
        public String processAnimal(Animal animal) {
            String response = "Received animal named: " + animal.name() + ".";
            if (animal instanceof Mammal) {
                response += " It says: " + ((Mammal) animal).sound();
            }
            return response;
        }

        @Post("/zoo")
        public String processZoo(Zoo zoo) {
            return String.format("Received %d animals", zoo.animals.size());
        }

        @Post("/animal/record")
        public String processAnimalRecord(Animal animal) {
            if (animal instanceof Dog) {
                return "Dog's vaccinations: " + ((Dog) animal).vetRecord().vaccinationHistory().keySet();
            }
            if (animal instanceof Cat) {
                return "Cat's vaccinations: " + ((Cat) animal).vetRecord().vaccinationHistory().keySet();
            }
            return "Unknown animal record.";
        }

        @Post("/animal/optional")
        public String processOptionalAnimal(Optional<Animal> animal) {
            return "Received optional animal: " + animal.map(Animal::name).orElse("empty");
        }
        // This method's purpose is to make DocService discover the ApiResponse<T> type.

        @Post("/dummy/api_response")
        public ApiResponse<Toy> getExampleResponse() {
            return new ApiResponse<>(200, null);
        }

        @Post("/misconfigured")
        public String processMisconfigured(MisconfiguredAnimal misconfigured) {
            return "Received: " + misconfigured.name();
        }

        @Post("/dummy/inventory")
        public Inventory getInventory() {
            return null;
        }
    }
}

