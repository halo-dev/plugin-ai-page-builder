package run.halo.aipagebuilder.memory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.utils.JsonUtils;
import run.halo.aipagebuilder.model.SiteMemory;
import run.halo.aipagebuilder.model.SiteMemory.Entry;
import run.halo.aipagebuilder.model.SiteMemory.Source;

@Service
public class SiteMemoryService {
    private static final int MAX_ENTRIES = 30;
    private static final int MAX_CONTENT_CHARACTERS = 8000;
    private static final int MAX_SOURCE_CHARACTERS = 24000;
    private static final int MAX_SERIALIZED_BYTES = 64 * 1024;

    private final ReactiveExtensionClient client;

    public SiteMemoryService(ReactiveExtensionClient client) {
        this.client = client;
    }

    public Mono<SiteMemory> load() {
        return Mono.defer(() -> client.fetch(SiteMemory.class, SiteMemory.NAME)
            .switchIfEmpty(Mono.defer(() -> client.create(emptyMemory()).flux().single()
                .onErrorResume(error -> isCreateConflict(error), error ->
                    client.fetch(SiteMemory.class, SiteMemory.NAME)
                        .switchIfEmpty(Mono.error(error))))));
    }

    public Mono<SiteMemory> apply(Long expectedVersion, List<Change> changes,
        SourceContext context) {
        return Mono.defer(() -> {
            if (expectedVersion == null || expectedVersion < 0) {
                throw invalid("expectedVersion is required and must not be negative");
            }
            if (changes == null || changes.isEmpty() || changes.size() > 5) {
                throw invalid("A batch must contain between 1 and 5 changes");
            }
            validateContext(context);
            return load().flatMap(current -> {
                if (!expectedVersion.equals(current.getMetadata().getVersion())) {
                    throw conflict("Site memory changed; reload before applying changes");
                }
                var updated = JsonUtils.deepCopy(current);
                var entries = new ArrayList<>(updated.getSpec().getEntries());
                var touchedKeys = new HashSet<MemoryKey>();
                var touchedIds = new HashSet<String>();
                boolean changed = false;
                for (var change : changes) {
                    changed |= applyChange(entries, change, context, touchedKeys, touchedIds);
                }
                if (changes.stream().anyMatch(change -> "delete".equals(change.action()))) {
                    // Shared quotes can retain forgotten facts in any spelling. Keep references only.
                    entries.replaceAll(entry -> {
                        var source = entry.source();
                        return new Entry(entry.id(), entry.subject(), entry.key(), entry.kind(),
                            entry.value(), new Source(source.pageName(), source.sessionId(),
                                source.runId(), source.sourceId(), "", source.actor()), entry.updatedAt());
                    });
                }
                updated.getSpec().setEntries(entries);
                validateCapacity(updated);
                if (!changed) {
                    return Mono.just(current);
                }
                // The fetched version is the storage CAS. Never rebase or retry a rejected write.
                return client.update(updated).flux().single();
            });
        }).onErrorMap(OptimisticLockingFailureException.class,
            error -> new ResponseStatusException(HttpStatus.CONFLICT,
                "Site memory changed; reload before applying changes", error));
    }

    private static boolean applyChange(List<Entry> entries, Change change, SourceContext context,
        Set<MemoryKey> touchedKeys, Set<String> touchedIds) {
        if (change == null || change.action() == null) {
            throw invalid("Each change must have an action");
        }
        if (!Set.of("add", "update", "delete").contains(change.action())) {
            throw invalid("action must be add, update or delete");
        }
        Entry existing = null;
        var operationKeys = new HashSet<MemoryKey>();
        if (!"add".equals(change.action())) {
            identifier(change.entryId(), "entryId", 64);
            if (touchedIds.contains(change.entryId())) {
                throw invalid("A batch must not change the same entry ID twice");
            }
            existing = entries.stream().filter(entry -> entry.id().equals(change.entryId()))
                .findFirst().orElseThrow(() -> conflict("Entry no longer exists"));
            operationKeys.add(key(existing));
        } else if (change.entryId() != null) {
            throw invalid("add must not supply an entryId");
        }
        var source = new Source(context.pageName(), context.sessionId(), context.runId(),
            identifier(change.sourceId(), "sourceId", 512),
            text(change.quote(), "quote", 500), context.actor());
        if ("delete".equals(change.action())) {
            if (change.subject() != null || change.key() != null || change.kind() != null
                || change.value() != null) {
                throw invalid("delete identifies the entry only by entryId");
            }
            touch(operationKeys, existing.id(), touchedKeys, touchedIds);
            entries.remove(existing);
            return true;
        }
        var subject = text(change.subject(), "subject", 100).strip();
        var key = text(change.key(), "key", 100).strip();
        if (!Set.of("fact", "brand_preference").contains(
            Objects.requireNonNullElse(change.kind(), ""))) {
            throw invalid("kind must be fact or brand_preference");
        }
        var value = text(change.value(), "value", 500).strip();
        var targetKey = new MemoryKey(normalizedKey(subject, "subject"),
            normalizedKey(key, "key"));
        operationKeys.add(targetKey);
        var occupied = entries.stream().filter(entry -> key(entry).equals(targetKey))
            .findFirst().orElse(null);
        if ("add".equals(change.action())) {
            touch(operationKeys, occupied == null ? null : occupied.id(), touchedKeys, touchedIds);
            if (occupied != null) {
                if (occupied.value().equals(value) && occupied.kind().equals(change.kind())) {
                    return false;
                }
                throw conflict("An entry with this subject and key already exists");
            }
            entries.add(new Entry(UUID.randomUUID().toString(), subject, key, change.kind(),
                value, source, Instant.now()));
            return true;
        }
        touch(operationKeys, existing.id(), touchedKeys, touchedIds);
        if (occupied != null && !occupied.id().equals(existing.id())) {
            throw conflict("An entry with this subject and key already exists");
        }
        if (key(existing).equals(targetKey) && existing.kind().equals(change.kind())
            && existing.value().equals(value)) {
            return false;
        }
        entries.set(entries.indexOf(existing), new Entry(existing.id(), subject, key,
            change.kind(), value, source, Instant.now()));
        return true;
    }

    private static void touch(Set<MemoryKey> keys, String id, Set<MemoryKey> touchedKeys,
        Set<String> touchedIds) {
        if (keys.stream().anyMatch(touchedKeys::contains)
            || id != null && touchedIds.contains(id)) {
            throw invalid("A batch must not change the same subject/key or entry ID twice");
        }
        touchedKeys.addAll(keys);
        if (id != null) {
            touchedIds.add(id);
        }
    }

    private static MemoryKey key(Entry entry) {
        return new MemoryKey(normalizedKey(entry.subject(), "subject"),
            normalizedKey(entry.key(), "key"));
    }

    private static String normalizedKey(String value, String field) {
        var normalized = text(value, field, 100).strip()
            .replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT);
        return text(normalized, field, 100);
    }

    private static String identifier(String value, String field, int limit) {
        text(value, field, limit);
        if (value.codePoints().anyMatch(code -> Character.isWhitespace(code)
            || Character.isSpaceChar(code) || Character.isISOControl(code))) {
            throw invalid(field + " must not contain whitespace or control characters");
        }
        return value;
    }

    private static String text(String value, String field, int limit) {
        if (value == null || value.isBlank() || value.length() > limit
            || value.codePoints().anyMatch(code -> Character.isISOControl(code)
                && code != '\n' && code != '\r' && code != '\t')) {
            throw invalid(field + " must contain 1 to " + limit + " characters without controls");
        }
        return value;
    }

    private static void validateContext(SourceContext context) {
        if (context == null) {
            throw invalid("Source context is required");
        }
        identifier(context.pageName(), "pageName", 253);
        identifier(context.sessionId(), "sessionId", 253);
        identifier(context.runId(), "runId", 253);
        identifier(context.actor(), "actor", 253);
    }

    private static void validateCapacity(SiteMemory memory) {
        var entries = memory.getSpec().getEntries();
        if (entries.size() > MAX_ENTRIES) {
            throw capacity("Site memory supports at most 30 entries");
        }
        int contentCharacters = 0;
        int sourceCharacters = 0;
        for (var entry : entries) {
            contentCharacters += entry.subject().length() + entry.key().length()
                + entry.value().length();
            var source = entry.source();
            sourceCharacters += source.pageName().length() + source.sessionId().length()
                + source.runId().length() + source.sourceId().length() + source.quote().length()
                + source.actor().length();
        }
        if (contentCharacters > MAX_CONTENT_CHARACTERS) {
            throw capacity("Site memory subject/key/value total must not exceed 8000 characters");
        }
        if (sourceCharacters > MAX_SOURCE_CHARACTERS) {
            throw capacity("Site memory source metadata must not exceed 24000 characters");
        }
        if (JsonUtils.objectToJson(memory).getBytes(StandardCharsets.UTF_8).length
            > MAX_SERIALIZED_BYTES) {
            throw capacity("Serialized site memory must not exceed 64 KiB");
        }
    }

    private static boolean isCreateConflict(Throwable error) {
        return error instanceof DuplicateKeyException
            || error instanceof OptimisticLockingFailureException
            || error instanceof ResponseStatusException status
                && status.getStatusCode().value() == 400
                && status.getCause() instanceof DuplicateKeyException;
    }

    private static SiteMemory emptyMemory() {
        var memory = new SiteMemory();
        var metadata = new Metadata();
        metadata.setName(SiteMemory.NAME);
        memory.setMetadata(metadata);
        memory.setSpec(new SiteMemory.Spec());
        return memory;
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private static ResponseStatusException capacity(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private record MemoryKey(String subject, String key) {
    }

    public record Change(String action, String entryId, String subject, String key, String kind,
                         String value, String sourceId, String quote) {
    }

    public record SourceContext(String pageName, String sessionId, String runId, String actor) {
    }
}
