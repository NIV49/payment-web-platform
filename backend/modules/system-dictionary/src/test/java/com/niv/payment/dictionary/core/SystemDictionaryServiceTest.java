package com.niv.payment.dictionary.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.ArrayDeque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemDictionaryServiceTest {
    private static final DictionaryModels.Actor PLATFORM =
        new DictionaryModels.Actor(1L, 2L, 3L, 4L, 5L, "PLATFORM");

    @Test
    void batchDeduplicatesTypesPreservesFirstOrderAndUsesOneBoundedRepositoryQuery() {
        RecordingRepository repository = new RecordingRepository();
        repository.revision = 7L;
        repository.batch.put("PAY_CHANNEL", List.of(
            new DictionaryModels.DisplayValue("bank", "Bank", "processing")));
        RecordingCache cache = new RecordingCache();
        var service = new SystemDictionaryService(repository, cache);

        Map<String, List<DictionaryModels.DisplayValue>> result = service.queryBatch(
            List.of("PAY_CHANNEL", "CASH_MODEL", "PAY_CHANNEL"));

        assertThat(result.keySet()).containsExactly("PAY_CHANNEL", "CASH_MODEL");
        assertThat(result.get("PAY_CHANNEL")).hasSize(1);
        assertThat(result.get("CASH_MODEL")).isEmpty();
        assertThat(repository.batchQueries).containsExactly(Set.of("PAY_CHANNEL", "CASH_MODEL"));
        assertThat(cache.storedRevision).isEqualTo(7L);
    }

    @Test
    void batchFallsBackToOneDatabaseQueryWhenSharedCacheIsUnavailable() {
        RecordingRepository repository = new RecordingRepository();
        repository.revision = 11L;
        RecordingCache cache = new RecordingCache();
        cache.failure = new IllegalStateException("redis unavailable");
        var service = new SystemDictionaryService(repository, cache);

        Map<String, List<DictionaryModels.DisplayValue>> result =
            service.queryBatch(List.of("PAY_CHANNEL", "CASH_MODEL"));

        assertThat(result).containsOnlyKeys("PAY_CHANNEL", "CASH_MODEL");
        assertThat(repository.batchQueries).hasSize(1);
    }

    @Test
    void batchRejectsUnboundedOrInvalidTypesBeforeCallingAdapters() {
        RecordingRepository repository = new RecordingRepository();
        var service = new SystemDictionaryService(repository, new RecordingCache());

        assertThatThrownBy(() -> service.queryBatch(List.of()))
            .isInstanceOf(SystemDictionaryException.InvalidRequest.class);
        assertThatThrownBy(() -> service.queryBatch(
            java.util.stream.IntStream.range(0, 65).mapToObj(index -> "TYPE_" + index).toList()))
            .isInstanceOf(SystemDictionaryException.InvalidRequest.class);
        assertThat(service.queryBatch(List.of("sys_user_sex")))
            .containsOnlyKeys("SYS_USER_SEX");
        assertThatThrownBy(() -> service.queryBatch(List.of("bad-type")))
            .isInstanceOf(SystemDictionaryException.InvalidRequest.class);
        assertThat(repository.batchQueries).containsExactly(Set.of("SYS_USER_SEX"));
    }

    @Test
    void mutationsFailClosedOutsidePlatformDomainAndAdvanceThroughRepository() {
        RecordingRepository repository = new RecordingRepository();
        var service = new SystemDictionaryService(repository, new RecordingCache());
        var command = new DictionaryCommands.CreateType("PAY_CHANNEL", "Payment channel", 0, "");

        assertThatThrownBy(() -> service.createType(
            new DictionaryModels.Actor(1L, 2L, 3L, 4L, 5L, "MERCHANT"), command))
            .isInstanceOf(SecurityException.class);

        assertThat(service.createType(PLATFORM, command)).isEqualTo(41L);
        assertThat(repository.createdTypes).containsExactly(command);
    }

    @Test
    void dataColorDefaultsAndRejectsValuesOutsideTheContractEnum() {
        RecordingRepository repository = new RecordingRepository();
        var service = new SystemDictionaryService(repository, new RecordingCache());

        service.createData(PLATFORM,
            new DictionaryCommands.CreateData("PAY_CHANNEL", "Bank", "bank", "", 0, ""));
        assertThat(repository.createdData.getFirst().color()).isEqualTo("default");
        assertThatThrownBy(() -> service.createData(PLATFORM,
            new DictionaryCommands.CreateData("PAY_CHANNEL", "Bank", "bank", "blue", 0, "")))
            .isInstanceOf(SystemDictionaryException.InvalidRequest.class);
    }

    @Test
    void batchRejectsMoreThanFiveThousandValuesWithoutReturningPartialData() {
        RecordingRepository repository = new RecordingRepository();
        repository.batch.put("TYPE_A", java.util.stream.IntStream.range(0, 5_001)
            .mapToObj(index -> new DictionaryModels.DisplayValue(
                Integer.toString(index), "Label " + index, "default"))
            .toList());
        var service = new SystemDictionaryService(repository, new RecordingCache());

        assertThatThrownBy(() -> service.queryBatch(List.of("TYPE_A")))
            .isInstanceOf(SystemDictionaryException.DataConflict.class);
        assertThat(repository.batchQueries).hasSize(1);
    }

    @Test
    void batchDiscardsMixedRevisionAndRetriesBeforePopulatingCache() {
        RecordingRepository repository = new RecordingRepository();
        repository.revisions.addAll(List.of(7L, 8L, 8L, 8L));
        repository.batch.put("TYPE_A", List.of(
            new DictionaryModels.DisplayValue("new", "New", "success")));
        RecordingCache cache = new RecordingCache();
        var service = new SystemDictionaryService(repository, cache);

        assertThat(service.queryBatch(List.of("TYPE_A")).get("TYPE_A").getFirst().value())
            .isEqualTo("new");
        assertThat(repository.batchQueries).hasSize(2);
        assertThat(cache.storeCalls).isEqualTo(1);
        assertThat(cache.storedRevision).isEqualTo(8L);
    }

    private static final class RecordingRepository implements DictionaryCatalogRepository {
        private long revision;
        private final ArrayDeque<Long> revisions = new ArrayDeque<>();
        private final Map<String, List<DictionaryModels.DisplayValue>> batch = new LinkedHashMap<>();
        private final List<Set<String>> batchQueries = new ArrayList<>();
        private final List<DictionaryCommands.CreateType> createdTypes = new ArrayList<>();
        private final List<DictionaryCommands.CreateData> createdData = new ArrayList<>();

        @Override public long currentRevision() {
            return revisions.isEmpty() ? revision : revisions.removeFirst();
        }
        @Override public Map<String, List<DictionaryModels.DisplayValue>> findDisplayValues(Set<String> types) {
            batchQueries.add(Set.copyOf(types));
            Map<String, List<DictionaryModels.DisplayValue>> result = new LinkedHashMap<>();
            types.forEach(type -> result.put(type, batch.getOrDefault(type, List.of())));
            return result;
        }
        @Override public long createType(DictionaryModels.Actor actor, DictionaryCommands.CreateType command) {
            createdTypes.add(command);
            return 41L;
        }
        @Override public DictionaryModels.Page<DictionaryModels.DictionaryType> findTypes(
            DictionaryCommands.TypeQuery query) { return new DictionaryModels.Page<>(List.of(), 0); }
        @Override public List<DictionaryModels.TypeOption> findTypeOptions() { return List.of(); }
        @Override public DictionaryModels.Page<DictionaryModels.DictionaryData> findData(
            DictionaryCommands.DataQuery query) { return new DictionaryModels.Page<>(List.of(), 0); }
        @Override public void updateType(DictionaryModels.Actor actor, long id,
                                         DictionaryCommands.UpdateType command) { }
        @Override public void deleteType(DictionaryModels.Actor actor, long id, long version) { }
        @Override public long createData(DictionaryModels.Actor actor,
                                         DictionaryCommands.CreateData command) {
            createdData.add(command);
            return 0;
        }
        @Override public void updateData(DictionaryModels.Actor actor, long id,
                                         DictionaryCommands.UpdateData command) { }
        @Override public void deleteData(DictionaryModels.Actor actor, long id, long version) { }
    }

    private static final class RecordingCache implements DictionaryBatchCache {
        private RuntimeException failure;
        private long storedRevision = -1;
        private int storeCalls;

        @Override
        public Optional<Map<String, List<DictionaryModels.DisplayValue>>> find(
            long revision, List<String> types) {
            if (failure != null) throw failure;
            return Optional.of(Map.of());
        }

        @Override
        public void store(long revision, Map<String, List<DictionaryModels.DisplayValue>> values) {
            storedRevision = revision;
            storeCalls++;
        }
    }
}
