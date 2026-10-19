package run.halo.aipagebuilder;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/** Shared Jackson mapper for plugin data. */
public final class Json {
    // Matches the former JsonUtils mapper: omit nulls and keep declaration order, which
    // Jackson 3 otherwise changes by sorting properties alphabetically.
    public static final JsonMapper MAPPER = JsonMapper.builder()
        .changeDefaultPropertyInclusion(
            v -> v.withValueInclusion(NON_NULL).withContentInclusion(NON_NULL))
        .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .disable(MapperFeature.SORT_CREATOR_PROPERTIES_FIRST)
        .build();

    private Json() { }

    public static String toJson(Object source) {
        return MAPPER.writeValueAsString(source);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }

    @SuppressWarnings("unchecked")
    public static <T> T deepCopy(T source) {
        return (T) fromJson(toJson(source), source.getClass());
    }
}
