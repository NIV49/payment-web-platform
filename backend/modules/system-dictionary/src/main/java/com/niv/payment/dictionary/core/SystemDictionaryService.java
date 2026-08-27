package com.niv.payment.dictionary.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class SystemDictionaryService {
    private static final Pattern DICT_TYPE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
    private static final Set<String> COLORS = Set.of(
        "default", "processing", "success", "warning", "error", "purple");
    private static final int MAX_BATCH_TYPES = 64;
    private static final int MAX_BATCH_VALUES = 5_000;
    private static final int MAX_REVISION_ATTEMPTS = 3;

    private final DictionaryCatalogRepository repository;
    private final DictionaryBatchCache cache;

    public SystemDictionaryService(DictionaryCatalogRepository repository, DictionaryBatchCache cache) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    public DictionaryModels.Page<DictionaryModels.DictionaryType> findTypes(
        DictionaryCommands.TypeQuery query) {
        Objects.requireNonNull(query, "query");
        return repository.findTypes(new DictionaryCommands.TypeQuery(
            optionalType(query.dictType()), optionalText(query.dictName(), 100, "dictName"),
            page(query.page()), pageSize(query.pageSize())));
    }

    public List<DictionaryModels.TypeOption> findTypeOptions() {
        return List.copyOf(repository.findTypeOptions());
    }

    public DictionaryModels.Page<DictionaryModels.DictionaryData> findData(
        DictionaryCommands.DataQuery query) {
        Objects.requireNonNull(query, "query");
        return repository.findData(new DictionaryCommands.DataQuery(
            type(query.dictType()), optionalText(query.label(), 100, "label"),
            optionalText(query.value(), 100, "value"), page(query.page()), pageSize(query.pageSize())));
    }

    public Map<String, List<DictionaryModels.DisplayValue>> queryBatch(List<String> requestedTypes) {
        if (requestedTypes == null || requestedTypes.isEmpty()
            || requestedTypes.size() > MAX_BATCH_TYPES) {
            throw invalid("dictTypes must contain 1 through 64 entries");
        }
        LinkedHashSet<String> canonicalTypes = new LinkedHashSet<>();
        requestedTypes.forEach(raw -> canonicalTypes.add(type(raw)));
        List<String> orderedTypes = List.copyOf(canonicalTypes);
        for (int attempt = 0; attempt < MAX_REVISION_ATTEMPTS; attempt++) {
            Map<String, List<DictionaryModels.DisplayValue>> result =
                queryBatchAtStableRevision(orderedTypes);
            if (result != null) return result;
        }
        throw new SystemDictionaryException.DataConflict(
            "Dictionary catalog changed repeatedly; retry the request");
    }

    private Map<String, List<DictionaryModels.DisplayValue>> queryBatchAtStableRevision(
        List<String> orderedTypes) {
        long revision = repository.currentRevision();

        Map<String, List<DictionaryModels.DisplayValue>> cached = null;
        try {
            cached = cache.find(revision, orderedTypes).orElse(null);
        } catch (RuntimeException ignored) {
            // PostgreSQL is authoritative; cache failures must not fail dictionary reads.
        }

        Set<String> misses = new LinkedHashSet<>(orderedTypes);
        if (cached != null) misses.removeAll(cached.keySet());
        Map<String, List<DictionaryModels.DisplayValue>> loaded = misses.isEmpty()
            ? Map.of()
            : repository.findDisplayValues(Set.copyOf(misses));
        Map<String, List<DictionaryModels.DisplayValue>> result = new LinkedHashMap<>();
        for (String dictType : orderedTypes) {
            List<DictionaryModels.DisplayValue> values = cached != null && cached.containsKey(dictType)
                ? cached.get(dictType)
                : loaded.getOrDefault(dictType, List.of());
            result.put(dictType, List.copyOf(values));
        }
        long totalValues = result.values().stream().mapToLong(List::size).sum();
        if (totalValues > MAX_BATCH_VALUES) {
            throw new SystemDictionaryException.DataConflict(
                "Dictionary batch exceeds the maximum result size");
        }
        if (repository.currentRevision() != revision) return null;
        if (!misses.isEmpty()) {
            Map<String, List<DictionaryModels.DisplayValue>> completeMisses = new LinkedHashMap<>();
            misses.forEach(type -> completeMisses.put(type,
                List.copyOf(loaded.getOrDefault(type, List.of()))));
            try {
                cache.store(revision, completeMisses);
            } catch (RuntimeException ignored) {
                // Cache population is best effort and never part of catalog truth.
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public long createType(DictionaryModels.Actor actor, DictionaryCommands.CreateType command) {
        requirePlatform(actor);
        Objects.requireNonNull(command, "command");
        return repository.createType(actor, new DictionaryCommands.CreateType(
            type(command.dictType()), text(command.dictName(), 100, "dictName"),
            sort(command.sort()), remark(command.remark())));
    }

    public void updateType(DictionaryModels.Actor actor, long id, DictionaryCommands.UpdateType command) {
        requirePlatform(actor);
        positiveId(id);
        Objects.requireNonNull(command, "command");
        repository.updateType(actor, id, new DictionaryCommands.UpdateType(
            type(command.dictType()), text(command.dictName(), 100, "dictName"),
            sort(command.sort()), remark(command.remark()), version(command.expectedVersion())));
    }

    public void deleteType(DictionaryModels.Actor actor, long id, long expectedVersion) {
        requirePlatform(actor);
        repository.deleteType(actor, positiveId(id), version(expectedVersion));
    }

    public long createData(DictionaryModels.Actor actor, DictionaryCommands.CreateData command) {
        requirePlatform(actor);
        Objects.requireNonNull(command, "command");
        return repository.createData(actor, new DictionaryCommands.CreateData(
            type(command.dictType()), text(command.label(), 100, "label"),
            text(command.value(), 100, "value"), color(command.color()),
            sort(command.sort()), remark(command.remark())));
    }

    public void updateData(DictionaryModels.Actor actor, long id, DictionaryCommands.UpdateData command) {
        requirePlatform(actor);
        positiveId(id);
        Objects.requireNonNull(command, "command");
        repository.updateData(actor, id, new DictionaryCommands.UpdateData(
            type(command.dictType()), text(command.label(), 100, "label"),
            text(command.value(), 100, "value"), color(command.color()),
            sort(command.sort()), remark(command.remark()), version(command.expectedVersion())));
    }

    public void deleteData(DictionaryModels.Actor actor, long id, long expectedVersion) {
        requirePlatform(actor);
        repository.deleteData(actor, positiveId(id), version(expectedVersion));
    }

    private static void requirePlatform(DictionaryModels.Actor actor) {
        Objects.requireNonNull(actor, "actor");
        if (actor.tenantId() <= 0 || actor.membershipId() <= 0 || actor.userId() <= 0
            || actor.permissionVersion() < 0 || actor.sessionVersion() < 0
            || !"PLATFORM".equals(actor.accountDomain())) {
            throw new SecurityException("Dictionary mutations require a trusted PLATFORM actor");
        }
    }

    private static String type(String value) {
        if (value == null || !DICT_TYPE.matcher(value.trim()).matches()) {
            throw invalid("dictType is invalid");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String optionalType(String value) {
        return value == null || value.isBlank() ? null : type(value);
    }

    private static String text(String value, int max, String field) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw invalid(field + " is invalid");
        }
        return value.trim();
    }

    private static String optionalText(String value, int max, String field) {
        return value == null || value.isBlank() ? null : text(value, max, field);
    }

    private static String remark(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        if (normalized.length() > 500) throw invalid("remark is invalid");
        return normalized;
    }

    private static String color(String value) {
        if (value == null || value.isBlank()) return "default";
        String normalized = value.trim();
        if (!COLORS.contains(normalized)) throw invalid("color is invalid");
        return normalized;
    }

    private static int sort(int value) {
        if (value < 0 || value > 9999) throw invalid("sort is invalid");
        return value;
    }

    private static int page(int value) {
        if (value < 1) throw invalid("page is invalid");
        return value;
    }

    private static int pageSize(int value) {
        if (value < 1 || value > 200) throw invalid("pageSize is invalid");
        return value;
    }

    private static long positiveId(long value) {
        if (value <= 0) throw invalid("identifier is invalid");
        return value;
    }

    private static long version(long value) {
        if (value < 0) throw invalid("expectedVersion is invalid");
        return value;
    }

    private static SystemDictionaryException.InvalidRequest invalid(String message) {
        return new SystemDictionaryException.InvalidRequest(message);
    }
}
