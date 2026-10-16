package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.databind.util.LookupCache;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.Explode;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.enums.ParameterStyle;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class PublicApiSchemaResolverTest {
    private final PublicApiSchemaResolver resolver = new PublicApiSchemaResolver();

    @Test
    void resolvesArrayEnumsDefaultsAndSerialization() throws Exception {
        var contract = resolve("parameters");
        var parameters = parameters(contract);
        assertThat(parameters).hasSize(3);
        assertThat(parameters.getFirst()).containsEntry("name", "tags")
            .containsEntry("in", "query").containsEntry("required", true)
            .containsEntry("style", "form").containsEntry("explode", false);
        var array = map(parameters.getFirst().get("schema"));
        assertThat(array).containsEntry("type", "array").containsEntry("minItems", 1);
        assertThat(map(array.get("items"))).containsEntry("type", "string")
            .containsEntry("enum", List.of("a", "b")).containsEntry("default", "a");
        assertThat(map(parameters.get(1).get("schema")))
            .containsEntry("type", "integer").containsEntry("default", 20)
            .containsEntry("format", "int32");
        assertThat(parameters.get(2)).containsEntry("required", true).containsEntry("in", "path");
        assertThat(contract).doesNotContainKey("partial");
    }

    @Test
    void explicitAnnotationOverridesImplementationAndPreservesConstraints() throws Exception {
        var parameters = parameters(resolve("overrides"));
        assertThat(map(parameters.getFirst().get("schema")))
            .containsEntry("type", "string").containsEntry("enum", List.of("one", "two"))
            .containsEntry("default", "one").doesNotContainKeys("$ref", "properties");
        assertThat(map(parameters.get(1).get("schema")))
            .containsEntry("type", "integer").containsEntry("default", 7)
            .containsEntry("description", "Maximum results");
        var array = map(parameters.get(2).get("schema"));
        assertThat(map(array.get("items"))).containsEntry("type", "string")
            .containsEntry("enum", List.of("small", "large"));
    }

    @Test
    void resolvesRequestBodyResponseMediaAndStatusCodes() throws Exception {
        var contract = resolve("bodyAndResponses");
        var body = map(contract.get("requestBody"));
        assertThat(body).containsEntry("required", true)
            .containsEntry("description", "Create node");
        var content = map(body.get("content"));
        assertThat(content).containsOnlyKeys("application/json", "application/xml");
        assertThat(map(map(content.get("application/json")).get("schema")))
            .containsEntry("$ref", "#/components/schemas/Node");
        var responses = map(contract.get("responses"));
        assertThat(responses).containsOnlyKeys("201", "204", "default");
        assertThat(map(responses.get("204"))).doesNotContainKey("content");
        assertThat(map(map(map(responses.get("201")).get("content"))
            .get("application/json"))).containsKey("schema");
        assertThat(contract).doesNotContainKey("partial");
    }

    @Test
    void closesRecursiveReferencesAndDoesNotLeakModelsAcrossCalls() throws Exception {
        var contract = resolve("bodyAndResponses");
        var schemas = map(map(contract.get("components")).get("schemas"));
        assertThat(schemas).containsOnlyKeys("Node");
        var properties = map(map(schemas.get("Node")).get("properties"));
        assertThat(map(properties.get("next")))
            .containsEntry("$ref", "#/components/schemas/Node");
        assertThat(map(map(resolve("parameters").get("components")).get("schemas"))).isEmpty();
    }

    @Test
    void marksExternalReferencesAndUnsupportedFieldsPartial() throws Exception {
        var contract = resolve("partial");
        assertThat(contract).containsEntry("partial", true);
        assertThat((List<?>) contract.get("warnings")).anySatisfy(warning ->
            assertThat(warning.toString()).contains("https://example.invalid/schema.json"))
            .anySatisfy(warning -> assertThat(warning.toString()).contains("ApiResponse.headers"))
            .anySatisfy(warning -> assertThat(warning.toString()).contains("useReturnTypeSchema"));
        assertThat(map(map(contract.get("components")).get("schemas"))).isEmpty();
    }

    @Test
    void resolvesActualSpringdocBuilderAnnotations() {
        var operation = org.springdoc.core.fn.builders.operation.Builder.operationBuilder()
            .operationId("getNode")
            .response(org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder()
                .implementation(Node.class))
            .build().getOperation();
        var contract = resolver.resolve(operation);
        assertThat(map(map(contract.get("components")).get("schemas"))).containsKey("Node");
        assertThat(contract).doesNotContainKeys("partial", "warnings");
    }

    @Test
    void marksActualNestedUnsupportedConstraintsPartial() throws Exception {
        var contract = resolve("nestedConstraints");
        assertThat(contract).containsEntry("partial", true);
        assertThat((List<?>) contract.get("warnings"))
            .anySatisfy(warning -> assertThat(warning.toString()).contains("Content.propertyNames"))
            .anySatisfy(warning -> assertThat(warning.toString()).contains("Content.not"))
            .anySatisfy(warning -> assertThat(warning.toString())
                .contains("Content.additionalPropertiesArraySchema"));
    }

    @Test
    void rejectsDanglingLocalReferences() {
        assertThatThrownBy(() -> resolve("missingRef"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unresolved local schema reference");
    }

    @Test
    void rejectsUnresolvedLocalExampleAndParameterReferences() {
        for (var fixture : List.of("localExampleRef", "localParameterRef")) {
            assertThatThrownBy(() -> resolve(fixture))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unresolved local reference");
        }
    }

    @Test
    void marksExternalExampleAndParameterReferencesPartial() throws Exception {
        var result = resolve("externalReferences");
        assertThat(result).containsEntry("partial", true);
        assertThat((List<?>) result.get("warnings"))
            .anySatisfy(warning -> assertThat(warning.toString())
                .contains("https://example.invalid/parameter.json"))
            .anySatisfy(warning -> assertThat(warning.toString())
                .contains("https://example.invalid/example.json"));
    }

    @Test
    void doesNotInterpretExamplePayloadReferencesAsContractReferences() throws Exception {
        assertThat(resolve("examplePayload")).doesNotContainKeys("partial", "warnings");
    }

    @Test
    void missingJavadocAndEmptyOperationAreNormal() throws Exception {
        var result = resolve("empty");
        assertThat(result).containsEntry("parameters", List.of())
            .containsEntry("responses", Map.of()).doesNotContainKeys("partial", "warnings");
    }

    @Test
    void explicitReferenceTakesPrecedenceOverImplementation() throws Exception {
        var result = resolve("refOverride");
        assertThat(map(parameters(result).getFirst().get("schema")))
            .containsEntry("$ref", "https://example.invalid/explicit.json");
        assertThat(map(map(result.get("components")).get("schemas"))).isEmpty();
    }

    @Test
    void rejectsExcessiveInlineDepthAsRecoverableArgumentError() throws Exception {
        assertThatThrownBy(() -> resolve("deep"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("depth");
        assertThat(resolve("empty")).doesNotContainKey("partial");
    }

    @Test
    void rejectsDifferentTypesWithTheSameComponentName() {
        assertThatThrownBy(() -> resolve("conflictingItems"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Conflicting API schema name 'Item'")
            .hasMessageContaining(Request.Item.class.getName())
            .hasMessageContaining(Response.Item.class.getName());
        assertThatThrownBy(() -> resolve("conflictingAliases"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Conflicting API schema name 'Alias'");
    }

    @Test
    void resolvesCoreHaloContentAndSearchModels() {
        for (var type : List.of(run.halo.app.core.extension.content.Post.class,
            run.halo.app.core.extension.content.Category.class,
            run.halo.app.search.SearchOption.class, run.halo.app.search.SearchResult.class)) {
            var operation = org.springdoc.core.fn.builders.operation.Builder.operationBuilder()
                .operationId("coreModel")
                .response(org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder()
                    .implementation(type)).build().getOperation();
            var contract = resolver.resolve(operation);
            assertThat(map(map(contract.get("components")).get("schemas")))
                .as(type.getName()).containsKey(type.getSimpleName());
            assertThat(contract).doesNotContainKey("partial");
        }
    }

    @Test
    void keepsPluginTypesOutOfGlobalCachesOnSuccessAndFailure() throws Exception {
        for (var fixture : List.of("pluginResponse", "pluginArray", "pluginNested",
            "unsafeComposition", "unsafeArrayComposition", "unsafeDtoArray",
            "unsafeEnumValue", "unsafeDisabledEnumValue", "unsafeDtoComposition",
            "unsafeSchemaProperties")) {
            var loader = new FixtureClassLoader();
            var fixtures = loader.loadClass(PluginFixtures.class.getName());
            var operation = fixtures.getDeclaredMethod(fixture).getAnnotation(Operation.class);
            assertNoPluginTypesInGlobalCaches(loader);
            if (fixture.startsWith("unsafe")) {
                assertThatThrownBy(() -> resolver.resolve(operation))
                    .as(fixture).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot safely resolve API");
            } else {
                var result = resolver.resolve(operation);
                var schemas = map(map(result.get("components")).get("schemas"));
                assertThat(schemas).as(fixture).containsKey("PluginNode");
                assertThat(map(map(schemas.get("PluginNode")).get("properties")))
                    .containsKeys("title", "next");
                assertThat(result).doesNotContainKey("partial");
            }
            assertNoPluginTypesInGlobalCaches(loader);
        }
    }

    @Test
    void allowsBootstrapSchemasAndIgnoresHiddenPropertiesWithoutCachingPluginTypes()
        throws Exception {
        var loader = new FixtureClassLoader();
        var fixtures = loader.loadClass(PluginFixtures.class.getName());
        assertNoPluginTypesInGlobalCaches(loader);
        var contract = resolver.resolve(fixtures.getDeclaredMethod("safeAnnotations")
            .getAnnotation(Operation.class));
        var schemas = map(map(contract.get("components")).get("schemas"));
        var properties = map(map(schemas.get("PluginSafeAnnotations")).get("properties"));
        assertThat(properties).containsOnlyKeys("values");
        assertThat(map(map(properties.get("values")).get("items")))
            .containsEntry("type", "string");
        var composition = resolver.resolve(fixtures.getDeclaredMethod("primitiveComposition")
            .getAnnotation(Operation.class));
        var response = map(map(composition.get("responses")).get("default"));
        var schema = map(map(map(response.get("content")).get("*/*")).get("schema"));
        assertThat((List<?>) schema.get("oneOf")).hasSize(2);
        assertThat(map(((List<?>) schema.get("oneOf")).getFirst()))
            .containsEntry("type", "string");
        assertThat(map(((List<?>) schema.get("oneOf")).get(1)))
            .containsEntry("type", "integer");
        assertNoPluginTypesInGlobalCaches(loader);
    }

    @SuppressWarnings("unchecked")
    private static void assertNoPluginTypesInGlobalCaches(ClassLoader loader) throws Exception {
        var field = TypeFactory.class.getDeclaredField("_typeCache");
        field.setAccessible(true);
        for (var factory : List.of(TypeFactory.defaultInstance(), Json.mapper().getTypeFactory())) {
            var cache = (LookupCache<Object, JavaType>) field.get(factory);
            var retained = new ArrayList<String>();
            cache.contents((key, value) -> {
                if (hasPluginType(value, loader)) {
                    retained.add(value.toCanonical());
                }
            });
            assertThat(retained).as("global cache plugin types").isEmpty();
        }
    }

    private static boolean hasPluginType(JavaType type, ClassLoader loader) {
        if (type.getRawClass().getClassLoader() == loader) {
            return true;
        }
        for (int index = 0; index < type.containedTypeCount(); index++) {
            if (hasPluginType(type.containedType(index), loader)) {
                return true;
            }
        }
        return false;
    }

    // Reload fixture bytecode without compilation or GC timing; every iteration gets fresh classes.
    private static final class FixtureClassLoader extends ClassLoader {
        FixtureClassLoader() {
            super(PublicApiSchemaResolverTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(PublicApiSchemaResolverTest.class.getName())
                && !name.startsWith(PublicApiSchemaResolverTest.class.getName() + "$")) {
                return super.loadClass(name, resolve);
            }
            var type = findLoadedClass(name);
            if (type == null) {
                try (var input = getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (input == null) {
                        throw new ClassNotFoundException(name);
                    }
                    var bytes = input.readAllBytes();
                    type = defineClass(name, bytes, 0, bytes.length);
                } catch (IOException exception) {
                    throw new ClassNotFoundException(name, exception);
                }
            }
            if (resolve) {
                resolveClass(type);
            }
            return type;
        }
    }

    static class Request {
        static class Item {
            public String query;
        }
    }

    static class Response {
        static class Item {
            public int count;
        }
    }

    @Schema(name = "Alias")
    static class FirstAlias {
        public String first;
    }

    @Schema(name = "Alias")
    static class SecondAlias {
        public int second;
    }

    public static class PluginNode {
        public String title;
        public PluginNode next;
    }

    public static class PluginEnvelope {
        public List<PluginNode> nodes;
        public Map<String, PluginNode> indexed;
        @Schema(implementation = PluginNode.class)
        public Object overridden;
    }

    public static class PluginArray {
        @ArraySchema(schema = @Schema(implementation = PluginNode.class))
        public List<Object> nodes;
    }

    public static class PluginComposition {
        @Schema(oneOf = PluginNode.class)
        public Object node;
    }

    public enum PluginEnum {
        VALUE;

        @JsonValue
        public PluginNode value() {
            return new PluginNode();
        }
    }

    public enum PluginDisabledEnum {
        VALUE;

        @JsonValue(false)
        public PluginNode value() {
            return new PluginNode();
        }
    }

    public static class PluginSafeAnnotations {
        @ArraySchema(schema = @Schema(implementation = String.class))
        public List<String> values;
        @Schema(hidden = true, oneOf = PluginNode.class)
        public Object internal;
        @com.fasterxml.jackson.annotation.JsonIgnore
        @Schema(oneOf = PluginNode.class)
        public Object ignored;
    }

    public static class PluginFixtures {
        @Operation(responses = @ApiResponse(content = @Content(
            schema = @Schema(implementation = PluginNode.class))))
        void pluginResponse() { }

        @Operation(responses = @ApiResponse(content = @Content(
            array = @ArraySchema(schema = @Schema(implementation = PluginNode.class)))))
        void pluginArray() { }

        @Operation(responses = @ApiResponse(content = @Content(
            schema = @Schema(implementation = PluginEnvelope.class))))
        void pluginNested() { }

        @Operation(responses = @ApiResponse(content = @Content(
            schema = @Schema(oneOf = PluginNode.class))))
        void unsafeComposition() { }

        @Operation(responses = @ApiResponse(content = @Content(
            array = @ArraySchema(schema = @Schema(allOf = PluginNode.class)))))
        void unsafeArrayComposition() { }

        @Operation(responses = @ApiResponse(content = @Content(
            schema = @Schema(implementation = PluginArray.class))))
        void unsafeDtoArray() { }

        @Operation(responses = @ApiResponse(content = @Content(
            schema = @Schema(implementation = PluginEnum.class))))
        void unsafeEnumValue() { }

        @Operation(responses = @ApiResponse(content = @Content(
            schema = @Schema(implementation = PluginDisabledEnum.class))))
        void unsafeDisabledEnumValue() { }

        @Operation(responses = @ApiResponse(content = @Content(
            schema = @Schema(implementation = PluginSafeAnnotations.class))))
        void safeAnnotations() { }

        @Operation(responses = @ApiResponse(content = @Content(
            schema = @Schema(oneOf = {String.class, Integer.class}))))
        void primitiveComposition() { }

        @Operation(responses = @ApiResponse(content = @Content(
            schema = @Schema(implementation = PluginComposition.class))))
        void unsafeDtoComposition() { }

        @Operation(responses = @ApiResponse(content = @Content(schema = @Schema(
            properties = @io.swagger.v3.oas.annotations.StringToClassMapItem(
                key = "node", value = PluginNode.class)))))
        void unsafeSchemaProperties() { }
    }

    private Map<String, Object> resolve(String method) throws Exception {
        return resolver.resolve(Fixtures.class.getDeclaredMethod(method)
            .getAnnotation(Operation.class));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parameters(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("parameters");
    }

    static class Node {
        public String title;
        public Node next;
    }

    static class Deep {
        public List<List<List<List<List<List<List<List<List<List<List<List<List<List<List<
            List<List<List<List<List<List<List<List<List<List<List<List<List<List<List<List<
            List<List<String>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> value;
    }

    static class Fixtures {
        @Operation(requestBody = @RequestBody(content = @Content(
            schema = @Schema(implementation = Request.Item.class))),
            responses = @ApiResponse(content = @Content(
                schema = @Schema(implementation = Response.Item.class))))
        void conflictingItems() { }

        @Operation(requestBody = @RequestBody(content = @Content(
            schema = @Schema(implementation = FirstAlias.class))),
            responses = @ApiResponse(content = @Content(
                schema = @Schema(implementation = SecondAlias.class))))
        void conflictingAliases() { }

        @Operation(parameters = {
            @Parameter(name = "tags", in = ParameterIn.QUERY, required = true,
                style = ParameterStyle.FORM, explode = Explode.FALSE,
                array = @ArraySchema(minItems = 1,
                    schema = @Schema(type = "string", allowableValues = {"a", "b"},
                        defaultValue = "a"))),
            @Parameter(name = "limit", in = ParameterIn.QUERY,
                schema = @Schema(type = "integer", format = "int32", defaultValue = "20")),
            @Parameter(name = "id", in = ParameterIn.PATH, schema = @Schema(type = "string")),
            @Parameter(name = "hidden", hidden = true)
        })
        void parameters() { }

        @Operation(parameters = {
            @Parameter(name = "kind", in = ParameterIn.QUERY,
                schema = @Schema(implementation = Node.class, type = "string",
                    allowableValues = {"one", "two"}, defaultValue = "one")),
            @Parameter(name = "limit", in = ParameterIn.QUERY,
                schema = @Schema(implementation = Integer.class, defaultValue = "7",
                    description = "Maximum results")),
            @Parameter(name = "sizes", in = ParameterIn.QUERY,
                array = @ArraySchema(schema = @Schema(implementation = Integer.class,
                    type = "string", allowableValues = {"small", "large"})))
        })
        void overrides() { }

        @Operation(requestBody = @RequestBody(required = true, description = "Create node",
            content = {
                @Content(mediaType = "application/json",
                    schema = @Schema(implementation = Node.class)),
                @Content(mediaType = "application/xml",
                    schema = @Schema(implementation = Node.class))
            }), responses = {
            @ApiResponse(responseCode = "201", description = "Created", content = @Content(
                mediaType = "application/json", array = @ArraySchema(
                    schema = @Schema(implementation = Node.class)))),
            @ApiResponse(responseCode = "204", description = "No content"),
            @ApiResponse(description = "Error", content = @Content(mediaType = "text/plain",
                schema = @Schema(type = "string")))
        })
        void bodyAndResponses() { }

        @Operation(parameters = @Parameter(name = "external", in = ParameterIn.QUERY,
            schema = @Schema(ref = "https://example.invalid/schema.json")),
            responses = @ApiResponse(
                responseCode = "200", useReturnTypeSchema = true,
                headers = @Header(name = "X-Count", schema = @Schema(type = "integer")),
                content = @Content(schema = @Schema(type = "string"))))
        void partial() { }

        @Operation(parameters = @Parameter(name = "external", in = ParameterIn.QUERY,
            schema = @Schema(ref = "https://example.invalid/explicit.json",
                implementation = Node.class)))
        void refOverride() { }

        @Operation(responses = @ApiResponse(content = @Content(
            schema = @Schema(implementation = Deep.class))))
        void deep() { }

        @Operation(responses = @ApiResponse(content = @Content(
            schema = @Schema(ref = "#/components/schemas/Missing"))))
        void missingRef() { }

        @Operation(responses = @ApiResponse(content = @Content(
            propertyNames = @Schema(pattern = "^[a-z]+$"),
            not = @Schema(type = "integer"),
            additionalPropertiesArraySchema = @ArraySchema(
                schema = @Schema(type = "string"), minItems = 1))))
        void nestedConstraints() { }

        @Operation(parameters = @Parameter(ref = "#/components/parameters/Missing"))
        void localParameterRef() { }

        @Operation(responses = @ApiResponse(content = @Content(examples = @ExampleObject(
            name = "sample", ref = "#/components/examples/Missing"))))
        void localExampleRef() { }

        @Operation(parameters = @Parameter(ref = "https://example.invalid/parameter.json"),
            responses = @ApiResponse(content = @Content(examples = @ExampleObject(
                name = "sample", ref = "https://example.invalid/example.json"))))
        void externalReferences() { }

        @Operation(responses = @ApiResponse(content = @Content(examples = @ExampleObject(
            name = "sample", value = "{\"$ref\":\"#/components/examples/BusinessValue\"}"))))
        void examplePayload() { }

        @Operation
        void empty() { }
    }
}
