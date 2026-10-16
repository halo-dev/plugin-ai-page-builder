package run.halo.aipagebuilder.ai;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.LRUMap;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.converter.ModelConverterContextImpl;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.AnnotationsUtils;
import io.swagger.v3.core.util.ObjectMapperFactory;
import io.swagger.v3.core.util.PrimitiveType;
import io.swagger.v3.core.util.ReflectionUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.Explode;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.enums.ParameterStyle;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.models.Components;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class PublicApiSchemaResolver {
    private static final String SCHEMA_REF = "#/components/schemas/";
    private static final int MAX_COMPONENTS = 128;
    private static final int MAX_DEPTH = 32;

    Map<String, Object> resolve(Operation operation) {
        Objects.requireNonNull(operation, "operation");
        try {
            return new Resolution().resolve(operation);
        } catch (StackOverflowError error) {
            throw new IllegalArgumentException("API schema exceeds resolution depth limit");
        }
    }

    // All converters, Jackson type caches and plugin classes live for this call only.
    private static final class Resolution {
        private final ObjectMapper mapper = ObjectMapperFactory.createJson();
        private final Components components = new Components();
        private final ModelConverterContextImpl context;
        private final Set<String> warnings = new LinkedHashSet<>();
        private final Map<String, Object> reachable = new LinkedHashMap<>();

        Resolution() {
            mapper.setTypeFactory(mapper.getTypeFactory().withCache(new LRUMap<>(16, 200)));
            context = new ModelConverterContextImpl(new LocalModelResolver()) {
                private final Map<String, JavaType> modelTypes = new LinkedHashMap<>();

                @Override
                public io.swagger.v3.oas.models.media.Schema resolve(AnnotatedType type) {
                    // ReferenceTypeUtils otherwise constructs Class/Type via global Json.mapper().
                    type.type(mapper.constructType(type.getType()));
                    return super.resolve(type);
                }

                @Override
                public void defineModel(String name, io.swagger.v3.oas.models.media.Schema model,
                    AnnotatedType type, String previousName) {
                    if (type != null && type.getType() != null) {
                        var javaType = mapper.constructType(type.getType());
                        var previous = modelTypes.putIfAbsent(name, javaType);
                        if (previous != null && !previous.equals(javaType)) {
                            throw new IllegalArgumentException("Conflicting API schema name '"
                                + name + "': " + previous.toCanonical() + " and "
                                + javaType.toCanonical());
                        }
                    }
                    super.defineModel(name, model, type, previousName);
                }
            };
        }

        private final class LocalModelResolver extends ModelResolver {
            private final Set<JavaType> inspected = new LinkedHashSet<>();

            LocalModelResolver() {
                super(mapper);
            }

            @Override
            public io.swagger.v3.oas.models.media.Schema resolve(AnnotatedType annotatedType,
                ModelConverterContext converterContext, Iterator<ModelConverter> chain) {
                // ModelResolver also recurses directly, bypassing ModelConverterContext.
                var type = mapper.constructType(annotatedType.getType());
                annotatedType.type(type);
                if (annotatedType.getCtxAnnotations() != null) {
                    for (var annotation : annotatedType.getCtxAnnotations()) {
                        safeAnnotation(annotation);
                    }
                }
                if (inspected.add(type)) {
                    var bean = mapper.getSerializationConfig().introspect(type);
                    safeAnnotation(bean.getClassInfo().getAnnotation(Schema.class));
                    safeAnnotation(bean.getClassInfo().getAnnotation(ArraySchema.class));
                    for (var property : bean.findProperties()) {
                        var member = property.getPrimaryMember();
                        if (member != null) {
                            var schema = member.getAnnotation(Schema.class);
                            if (schema != null && schema.hidden()
                                || member.hasAnnotation(io.swagger.v3.oas.annotations.Hidden.class)) {
                                continue;
                            }
                            for (var annotation : member.annotations()) {
                                safeAnnotation(annotation);
                            }
                        }
                    }
                    if (type.isEnumType()) {
                        // Match Swagger: enabled fields take precedence, but methods include false.
                        var valueType = Arrays.stream(type.getRawClass().getDeclaredFields())
                            .filter(field -> field.isAnnotationPresent(JsonValue.class)
                                && field.getAnnotation(JsonValue.class).value())
                            .map(java.lang.reflect.Field::getType).findFirst()
                            .orElseGet(() -> ReflectionUtils
                                .getAnnotatedMethods(type.getRawClass(), JsonValue.class).stream()
                                .map(java.lang.reflect.Method::getReturnType).findFirst().orElse(null));
                        if (valueType != null && !safeGlobalType(valueType)) {
                            throw new IllegalArgumentException(
                                "Cannot safely resolve API enum @JsonValue type: "
                                    + valueType.getName());
                        }
                    }
                }
                return super.resolve(annotatedType, converterContext, chain);
            }
        }

        private void safeAnnotation(Annotation annotation) {
            if (annotation instanceof ArraySchema array) {
                // ModelResolver has raw-Class and context-free paths for array implementations.
                if (!safeGlobalType(array.schema().implementation())) {
                    throw new IllegalArgumentException(
                        "Cannot safely resolve API DTO @ArraySchema implementation");
                }
                safeAnnotation(array.schema());
                safeAnnotation(array.arraySchema());
                safeAnnotation(array.contains());
                safeAnnotation(array.unevaluatedItems());
                for (var item : array.prefixItems()) {
                    safeAnnotation(item);
                }
            } else if (annotation instanceof Schema schema) {
                // AnnotationsUtils resolves these Classes before consulting the local context.
                if (!safeGlobalTypes(schema.not(), schema.additionalPropertiesSchema(),
                    schema.contentSchema(), schema.propertyNames(), schema._if(), schema._else(),
                    schema.then(), schema.unevaluatedProperties())
                    || !safeGlobalTypes(schema.oneOf()) || !safeGlobalTypes(schema.anyOf())
                    || !safeGlobalTypes(schema.allOf())
                    || Arrays.stream(schema.dependentSchemas())
                        .anyMatch(item -> !safeGlobalType(item.value()))
                    || Arrays.stream(schema.patternProperties())
                        .anyMatch(item -> !safeGlobalType(item.value()))
                    || Arrays.stream(schema.properties())
                        .anyMatch(item -> !safeGlobalType(item.value()))) {
                    throw new IllegalArgumentException(
                        "Cannot safely resolve API @Schema class-valued composition");
                }
            }
        }

        private boolean safeGlobalTypes(Class<?>... types) {
            return Arrays.stream(types).allMatch(this::safeGlobalType);
        }

        private boolean safeGlobalType(Class<?> type) {
            // Only bootstrap scalars may enter Swagger's raw-Class/global-cache shortcuts.
            return type == Void.class || type.getClassLoader() == null
                && PrimitiveType.fromType(mapper.constructType(type)) != null;
        }

        Map<String, Object> resolve(Operation operation) {
            var result = new LinkedHashMap<String, Object>();
            var parameters = new ArrayList<Map<String, Object>>();
            for (var annotation : operation.parameters()) {
                if (annotation.hidden()) {
                    continue;
                }
                var parameter = new LinkedHashMap<String, Object>();
                if (!annotation.ref().isBlank()) {
                    parameter.put("$ref", annotation.ref());
                } else {
                    parameter.put("name", annotation.name());
                    if (annotation.in() != ParameterIn.DEFAULT) {
                        parameter.put("in", annotation.in().toString());
                    } else {
                        warnings.add("Parameter location is unspecified: " + annotation.name());
                    }
                    parameter.put("required", annotation.required()
                        || annotation.in() == ParameterIn.PATH);
                    text(parameter, "description", annotation.description());
                    if (annotation.deprecated()) {
                        parameter.put("deprecated", true);
                    }
                    if (annotation.allowEmptyValue()) {
                        parameter.put("allowEmptyValue", true);
                    }
                    if (annotation.allowReserved()) {
                        parameter.put("allowReserved", true);
                    }
                    if (annotation.style() != ParameterStyle.DEFAULT) {
                        parameter.put("style", annotation.style().toString());
                    }
                    if (annotation.explode() != Explode.DEFAULT) {
                        parameter.put("explode", annotation.explode() == Explode.TRUE);
                    }
                    if (annotation.content().length > 0) {
                        parameter.put("content", content(annotation.content()));
                    } else {
                        var schema = schema(annotation.schema(), annotation.array());
                        parameter.put("schema", schema);
                        if (schema.isEmpty()) {
                            warnings.add("Parameter schema is unspecified: " + annotation.name());
                        }
                    }
                    text(parameter, "example", annotation.example());
                    examples(parameter, annotation.examples());
                }
                unsupported(annotation, Set.of("name", "in", "description", "required",
                    "deprecated", "allowEmptyValue", "allowReserved", "style", "explode",
                    "content", "schema", "array", "hidden", "example", "examples", "ref"));
                parameters.add(parameter);
            }
            result.put("parameters", parameters);
            var body = operation.requestBody();
            if (!body.ref().isBlank()) {
                result.put("requestBody", Map.of("$ref", body.ref()));
            } else if (body.content().length > 0 || body.required()
                || !body.description().isBlank() || body.useParameterTypeSchema()) {
                var requestBody = new LinkedHashMap<String, Object>();
                requestBody.put("required", body.required());
                text(requestBody, "description", body.description());
                requestBody.put("content", content(body.content()));
                result.put("requestBody", requestBody);
            }
            unsupported(body, Set.of("ref", "content", "required", "description"));
            var responses = new LinkedHashMap<String, Object>();
            for (var annotation : operation.responses()) {
                var response = new LinkedHashMap<String, Object>();
                if (!annotation.ref().isBlank()) {
                    response.put("$ref", annotation.ref());
                } else {
                    response.put("description", annotation.description());
                    if (annotation.content().length > 0) {
                        response.put("content", content(annotation.content()));
                    }
                }
                unsupported(annotation, Set.of("ref", "description", "content", "responseCode"));
                var code = annotation.responseCode().isBlank() ? "default"
                    : annotation.responseCode();
                if (responses.put(code, response) != null) {
                    warnings.add("Duplicate response code: " + code);
                }
            }
            result.put("responses", responses);
            unsupported(operation, Set.of("parameters", "requestBody", "responses", "method",
                "tags", "summary", "description", "operationId", "deprecated", "hidden"));
            visit(result, 0, "operation");
            result.put("components", Map.of("schemas", reachable));
            if (!warnings.isEmpty()) {
                result.put("partial", true);
                result.put("warnings", List.copyOf(warnings));
            }
            return result;
        }

        private Map<String, Object> content(Content[] annotations) {
            var result = new LinkedHashMap<String, Object>();
            for (var annotation : annotations) {
                var media = new LinkedHashMap<String, Object>();
                var schema = schema(annotation.schema(), annotation.array());
                if (!schema.isEmpty()) {
                    media.put("schema", schema);
                }
                examples(media, annotation.examples());
                unsupported(annotation, Set.of("mediaType", "schema", "array", "examples"));
                var mediaType = annotation.mediaType().isBlank() ? "*/*" : annotation.mediaType();
                if (result.put(mediaType, media) != null) {
                    warnings.add("Duplicate media type: " + mediaType);
                }
            }
            return result;
        }

        private Map<String, Object> schema(Schema annotation, ArraySchema array) {
            if (AnnotationsUtils.hasArrayAnnotation(array)) {
                safeAnnotation(array.schema());
                safeAnnotation(array.arraySchema());
                safeAnnotation(array.contains());
                safeAnnotation(array.unevaluatedItems());
                for (var item : array.prefixItems()) {
                    safeAnnotation(item);
                }
                var result = json(AnnotationsUtils.getArraySchema(array, components, null,
                    false, null, false, context).orElseThrow());
                var items = schema(array.schema());
                if (!items.isEmpty()) {
                    result.put("items", items);
                }
                return result;
            }
            return schema(annotation);
        }

        private Map<String, Object> schema(Schema annotation) {
            safeAnnotation(annotation);
            var explicit = AnnotationsUtils.getSchemaFromAnnotation(annotation, components,
                null, false, null, context).map(this::json).orElseGet(LinkedHashMap::new);
            if (!annotation.ref().isBlank() || annotation.implementation() == Void.class) {
                return normalize(explicit);
            }
            // Passing JavaType to PrimitiveType avoids TypeFactory.defaultInstance() caching DTOs.
            var type = mapper.constructType(annotation.implementation());
            var primitive = PrimitiveType.fromType(type);
            var model = primitive == null
                ? context.resolve(new AnnotatedType(type).components(components).resolveAsRef(true))
                : primitive.createProperty();
            if (model == null) {
                throw new IllegalArgumentException("Cannot resolve API schema: " + type);
            }
            var inferred = json(model);
            if (!annotation.type().isBlank() && !"object".equals(annotation.type())
                && !annotation.type().equals(inferred.get("type"))) {
                // An explicit primitive type must not inherit object properties or a model ref.
                return normalize(explicit);
            }
            if (inferred.containsKey("$ref") && !explicit.isEmpty()) {
                var allOf = new ArrayList<Object>();
                if (explicit.get("allOf") instanceof List<?> existing) {
                    allOf.addAll(existing);
                }
                allOf.add(inferred);
                explicit.put("allOf", allOf);
                return explicit;
            }
            inferred.putAll(explicit);
            return normalize(inferred);
        }

        private Map<String, Object> normalize(Map<String, Object> schema) {
            var type = schema.get("type");
            if (Set.of("integer", "number", "boolean").contains(type == null ? "" : type)) {
                if (schema.containsKey("default")) {
                    schema.put("default", scalar(schema.get("default"), type.toString()));
                }
                if (schema.get("enum") instanceof List<?> values) {
                    schema.put("enum", values.stream()
                        .map(value -> scalar(value, type.toString())).toList());
                }
            }
            return schema;
        }

        private Object scalar(Object value, String type) {
            if (!(value instanceof String text)) {
                return value;
            }
            try {
                var parsed = mapper.readTree(text);
                if (parsed != null && ("integer".equals(type) && parsed.isIntegralNumber()
                    || "number".equals(type) && parsed.isNumber()
                    || "boolean".equals(type) && parsed.isBoolean())) {
                    return mapper.convertValue(parsed, Object.class);
                }
            } catch (java.io.IOException ignored) {
                // Preserve the declaration, but do not present it as a complete contract.
            }
            warnings.add("Schema value does not match declared type: " + type);
            return value;
        }

        private void examples(Map<String, Object> target,
            io.swagger.v3.oas.annotations.media.ExampleObject[] annotations) {
            if (annotations.length == 0) {
                return;
            }
            var examples = new LinkedHashMap<String, Object>();
            for (var annotation : annotations) {
                if (!annotation.ref().isBlank()) {
                    reference(annotation.ref());
                }
                AnnotationsUtils.getExample(annotation)
                    .ifPresent(example -> examples.put(annotation.name(), json(example)));
            }
            target.put("examples", examples);
        }

        private Map<String, Object> json(Object value) {
            return mapper.convertValue(value,
                new TypeReference<LinkedHashMap<String, Object>>() { });
        }

        private void unsupported(Annotation annotation, Set<String> supported) {
            // Springdoc's functional builders implement annotations without annotationType().
            Class<? extends Annotation> annotationType = switch (annotation) {
                case Operation ignored -> Operation.class;
                case io.swagger.v3.oas.annotations.Parameter ignored ->
                    io.swagger.v3.oas.annotations.Parameter.class;
                case io.swagger.v3.oas.annotations.parameters.RequestBody ignored ->
                    io.swagger.v3.oas.annotations.parameters.RequestBody.class;
                case io.swagger.v3.oas.annotations.responses.ApiResponse ignored ->
                    io.swagger.v3.oas.annotations.responses.ApiResponse.class;
                case Content ignored -> Content.class;
                default -> annotation.annotationType();
            };
            for (var member : annotationType.getDeclaredMethods()) {
                if (supported.contains(member.getName())) {
                    continue;
                }
                try {
                    if (!matchesDefault(member.getDefaultValue(), member.invoke(annotation))) {
                        warnings.add("Unsupported annotation field: "
                            + annotationType.getSimpleName() + "." + member.getName());
                    }
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalArgumentException("Cannot inspect API annotation", exception);
                }
            }
        }

        private boolean matchesDefault(Object expected, Object actual)
            throws ReflectiveOperationException {
            // Builder annotations may use null for an unset member and identity-based equals.
            if (actual == null) {
                return true;
            }
            if (expected instanceof Annotation defaultAnnotation) {
                var type = defaultAnnotation.annotationType();
                if (!type.isInstance(actual)) {
                    return false;
                }
                for (var member : type.getDeclaredMethods()) {
                    if (!matchesDefault(member.invoke(defaultAnnotation), member.invoke(actual))) {
                        return false;
                    }
                }
                return true;
            }
            if (expected instanceof Object[] defaults && actual instanceof Object[] values) {
                if (defaults.length != values.length) {
                    return false;
                }
                for (int index = 0; index < defaults.length; index++) {
                    if (!matchesDefault(defaults[index], values[index])) {
                        return false;
                    }
                }
                return true;
            }
            return Objects.deepEquals(expected, actual);
        }

        private void visit(Object value, int depth, String position) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("API schema exceeds inline depth limit of 32");
            }
            if (value instanceof Map<?, ?> map) {
                if (map.get("$ref") instanceof String ref) {
                    reference(ref);
                }
                for (var entry : map.entrySet()) {
                    // Example/default payloads are data, not schema references.
                    if (Set.of("responses", "properties", "schemas", "content")
                        .contains(position)
                        || !Set.of("example", "examples", "default", "enum")
                        .contains(entry.getKey())) {
                        visit(entry.getValue(), depth + 1, entry.getKey().toString());
                    }
                }
            } else if (value instanceof List<?> list) {
                list.forEach(item -> visit(item, depth + 1, position));
            }
        }

        private void reference(String ref) {
            if (!ref.startsWith(SCHEMA_REF)) {
                if (ref.startsWith("#")) {
                    throw new IllegalArgumentException("Unresolved local reference: " + ref);
                }
                warnings.add("Unresolved reference: " + ref);
                return;
            }
            var name = ref.substring(SCHEMA_REF.length()).replace("~1", "/").replace("~0", "~");
            if (reachable.containsKey(name)) {
                return;
            }
            var model = components.getSchemas() == null ? null : components.getSchemas().get(name);
            if (model == null) {
                model = context.getDefinedModels().get(name);
            }
            if (model == null) {
                throw new IllegalArgumentException("Unresolved local schema reference: " + ref);
            }
            if (reachable.size() >= MAX_COMPONENTS) {
                throw new IllegalArgumentException("API schema exceeds component limit of 128");
            }
            var resolved = json(model);
            reachable.put(name, resolved);
            visit(resolved, 0, "schema");
        }

        private static void text(Map<String, Object> target, String key, String value) {
            if (!value.isBlank()) {
                target.put(key, value);
            }
        }
    }
}
