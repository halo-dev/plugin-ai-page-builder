package run.halo.aipagebuilder;

import java.util.Objects;
import java.util.function.Function;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.index.query.Condition;
import run.halo.app.extension.index.query.IndexCondition;

/** Evaluates selectors in storage fixtures so pagination tests exercise filtering order. */
public final class QueryTestSupport {
    public static boolean matches(ListOptions options, Function<String, String> value) {
        return matches((Condition) options.getFieldSelector().query(), value);
    }

    private static boolean matches(Condition query, Function<String, String> value) {
        var type = query.getClass().getSimpleName();
        if (query instanceof IndexCondition condition) {
            var actual = value.apply(condition.indexName());
            return switch (type) {
                case "IsNullCondition" -> actual == null;
                case "IsNotNullCondition" -> actual != null;
                case "EqualCondition" -> Objects.equals(actual, component(query, "key"));
                case "StringContainsCondition" -> actual != null && actual.contains((String) component(query, "keyword"));
                default -> throw new AssertionError(type);
            };
        }
        if (type.equals("NotCondition")) return !matches((Condition) component(query, "condition"), value);
        boolean left = matches((Condition) component(query, "left"), value);
        boolean right = matches((Condition) component(query, "right"), value);
        return type.equals("OrCondition") ? left || right : left && right;
    }

    private static Object component(Condition query, String name) {
        try {
            var accessor = query.getClass().getDeclaredMethod(name);
            accessor.setAccessible(true);
            return accessor.invoke(query);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
