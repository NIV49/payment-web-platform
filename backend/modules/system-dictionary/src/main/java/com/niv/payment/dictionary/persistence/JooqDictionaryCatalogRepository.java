package com.niv.payment.dictionary.persistence;

import com.niv.payment.dictionary.core.DictionaryCatalogRepository;
import com.niv.payment.dictionary.core.DictionaryCommands;
import com.niv.payment.dictionary.core.DictionaryModels;
import com.niv.payment.dictionary.core.SystemDictionaryException;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.jooq.impl.DSL;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public class JooqDictionaryCatalogRepository implements DictionaryCatalogRepository {
    private static final String ACTIVE = "ACTIVE";
    private static final int MAX_VALUES_PER_TYPE = 1_000;
    private static final int MAX_LIVE_TYPES = 1_000;
    private static final int MAX_BATCH_VALUES = 5_000;

    private static final Table<Record> TYPE = DSL.table(DSL.name("sys_dictionary_type"));
    private static final Field<Long> TYPE_ID = field(TYPE, "id", Long.class);
    private static final Field<String> TYPE_CODE = field(TYPE, "dict_type", String.class);
    private static final Field<String> TYPE_NAME = field(TYPE, "dict_name", String.class);
    private static final Field<Integer> TYPE_SORT = field(TYPE, "sort_order", Integer.class);
    private static final Field<String> TYPE_REMARK = field(TYPE, "remark", String.class);
    private static final Field<OffsetDateTime> TYPE_CREATED = field(TYPE, "created_at", OffsetDateTime.class);
    private static final Field<OffsetDateTime> TYPE_UPDATED = field(TYPE, "updated_at", OffsetDateTime.class);
    private static final Field<Long> TYPE_VERSION = field(TYPE, "row_version", Long.class);
    private static final Field<OffsetDateTime> TYPE_DELETED = field(TYPE, "deleted_at", OffsetDateTime.class);

    private static final Table<Record> DATA = DSL.table(DSL.name("sys_dictionary_data"));
    private static final Field<Long> DATA_ID = field(DATA, "id", Long.class);
    private static final Field<Long> DATA_TYPE_ID = field(DATA, "dictionary_type_id", Long.class);
    private static final Field<String> DATA_LABEL = field(DATA, "label", String.class);
    private static final Field<String> DATA_VALUE = field(DATA, "value", String.class);
    private static final Field<String> DATA_COLOR = field(DATA, "color", String.class);
    private static final Field<Integer> DATA_SORT = field(DATA, "sort_order", Integer.class);
    private static final Field<String> DATA_REMARK = field(DATA, "remark", String.class);
    private static final Field<OffsetDateTime> DATA_CREATED = field(DATA, "created_at", OffsetDateTime.class);
    private static final Field<OffsetDateTime> DATA_UPDATED = field(DATA, "updated_at", OffsetDateTime.class);
    private static final Field<Long> DATA_VERSION = field(DATA, "row_version", Long.class);
    private static final Field<OffsetDateTime> DATA_DELETED = field(DATA, "deleted_at", OffsetDateTime.class);

    private static final Table<Record> REVISION = DSL.table(DSL.name("sys_dictionary_catalog_revision"));
    private static final Field<Short> REVISION_ID = field(REVISION, "singleton_id", Short.class);
    private static final Field<Long> REVISION_VALUE = field(REVISION, "revision", Long.class);
    private static final Field<OffsetDateTime> REVISION_UPDATED =
        field(REVISION, "updated_at", OffsetDateTime.class);

    private static final Table<Record> TENANT = DSL.table(DSL.name("iam_tenant"));
    private static final Field<Long> TENANT_ID = field(TENANT, "id", Long.class);
    private static final Field<String> TENANT_DOMAIN = field(TENANT, "account_domain", String.class);
    private static final Field<String> TENANT_STATUS = field(TENANT, "status", String.class);
    private static final Table<Record> USER = DSL.table(DSL.name("iam_user"));
    private static final Field<Long> USER_ID = field(USER, "id", Long.class);
    private static final Field<String> USER_DOMAIN = field(USER, "account_domain", String.class);
    private static final Field<String> USER_STATUS = field(USER, "status", String.class);
    private static final Table<Record> MEMBERSHIP = DSL.table(DSL.name("iam_membership"));
    private static final Field<Long> MEMBERSHIP_ID = field(MEMBERSHIP, "id", Long.class);
    private static final Field<Long> MEMBERSHIP_TENANT_ID = field(MEMBERSHIP, "tenant_id", Long.class);
    private static final Field<Long> MEMBERSHIP_USER_ID = field(MEMBERSHIP, "user_id", Long.class);
    private static final Field<String> MEMBERSHIP_DOMAIN = field(MEMBERSHIP, "account_domain", String.class);
    private static final Field<String> MEMBERSHIP_STATUS = field(MEMBERSHIP, "status", String.class);
    private static final Field<Long> MEMBERSHIP_PERMISSION_VERSION =
        field(MEMBERSHIP, "permission_version", Long.class);
    private static final Field<Long> MEMBERSHIP_SESSION_VERSION =
        field(MEMBERSHIP, "session_version", Long.class);
    private static final Table<Record> CREDENTIAL = DSL.table(DSL.name("iam_authentication_credential"));
    private static final Field<Long> CREDENTIAL_USER_ID = field(CREDENTIAL, "user_id", Long.class);
    private static final Field<String> CREDENTIAL_DOMAIN = field(CREDENTIAL, "account_domain", String.class);
    private static final Field<String> CREDENTIAL_STATUS = field(CREDENTIAL, "status", String.class);

    private static final Table<Record> MEMBERSHIP_ROLE = DSL.table(DSL.name("iam_membership_role"));
    private static final Field<Long> MR_TENANT_ID = field(MEMBERSHIP_ROLE, "tenant_id", Long.class);
    private static final Field<Long> MR_MEMBERSHIP_ID = field(MEMBERSHIP_ROLE, "membership_id", Long.class);
    private static final Field<Long> MR_ROLE_ID = field(MEMBERSHIP_ROLE, "role_id", Long.class);
    private static final Table<Record> ROLE = DSL.table(DSL.name("iam_role"));
    private static final Field<Long> ROLE_ID = field(ROLE, "id", Long.class);
    private static final Field<Long> ROLE_TENANT_ID = field(ROLE, "tenant_id", Long.class);
    private static final Field<String> ROLE_STATUS = field(ROLE, "status", String.class);
    private static final Field<OffsetDateTime> ROLE_DELETED = field(ROLE, "deleted_at", OffsetDateTime.class);
    private static final Table<Record> GRANT = DSL.table(DSL.name("iam_role_grant"));
    private static final Field<Long> GRANT_ID = field(GRANT, "id", Long.class);
    private static final Field<Long> GRANT_TENANT_ID = field(GRANT, "tenant_id", Long.class);
    private static final Field<Long> GRANT_ROLE_ID = field(GRANT, "role_id", Long.class);
    private static final Field<Long> GRANT_PERMISSION_ID = field(GRANT, "permission_id", Long.class);
    private static final Field<String> GRANT_STATUS = field(GRANT, "status", String.class);
    private static final Field<OffsetDateTime> GRANT_VALID_FROM = field(GRANT, "valid_from", OffsetDateTime.class);
    private static final Field<OffsetDateTime> GRANT_VALID_UNTIL = field(GRANT, "valid_until", OffsetDateTime.class);
    private static final Table<Record> PERMISSION = DSL.table(DSL.name("iam_permission"));
    private static final Field<Long> PERMISSION_ID = field(PERMISSION, "id", Long.class);
    private static final Field<String> PERMISSION_CODE = field(PERMISSION, "permission_code", String.class);
    private static final Field<String> PERMISSION_STATUS = field(PERMISSION, "status", String.class);
    private static final Table<Record> DIMENSION = DSL.table(DSL.name("iam_grant_dimension"));
    private static final Field<Long> DIMENSION_GRANT_ID = field(DIMENSION, "grant_id", Long.class);
    private static final Field<String> DIMENSION_CODE = field(DIMENSION, "dimension_code", String.class);
    private static final Field<String> DIMENSION_MODE = field(DIMENSION, "scope_mode", String.class);

    private static final Table<Record> AUDIT = DSL.table(DSL.name("iam_audit_event"));
    private static final Field<Long> AUDIT_ID = field(AUDIT, "id", Long.class);
    private static final Field<Long> AUDIT_TENANT_ID = field(AUDIT, "tenant_id", Long.class);
    private static final Field<Long> AUDIT_OPERATOR = field(AUDIT, "operator_membership_id", Long.class);
    private static final Field<String> AUDIT_TARGET_TYPE = field(AUDIT, "target_type", String.class);
    private static final Field<String> AUDIT_TARGET_REF = field(AUDIT, "target_ref", String.class);
    private static final Field<String> AUDIT_ACTION = field(AUDIT, "action_code", String.class);
    private static final Field<String> AUDIT_DECISION = field(AUDIT, "decision", String.class);
    private static final Field<String> AUDIT_REASON = field(AUDIT, "reason_code", String.class);
    private static final Field<String> AUDIT_PERMISSION = field(AUDIT, "permission_code", String.class);
    private static final Field<JSONB> AUDIT_AFTER = field(AUDIT, "after_value", JSONB.class);
    private static final Field<String> AUDIT_TRACE = field(AUDIT, "trace_id", String.class);

    private final DSLContext dsl;
    private final Supplier<String> traceIdSupplier;

    public JooqDictionaryCatalogRepository(DSLContext dsl, Supplier<String> traceIdSupplier) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.traceIdSupplier = Objects.requireNonNull(traceIdSupplier, "traceIdSupplier");
    }

    @Override
    public DictionaryModels.Page<DictionaryModels.DictionaryType> findTypes(
        DictionaryCommands.TypeQuery query) {
        Condition condition = TYPE_DELETED.isNull();
        if (query.dictType() != null) condition = condition.and(TYPE_CODE.eq(query.dictType()));
        if (query.dictName() != null) condition = condition.and(TYPE_NAME.containsIgnoreCase(query.dictName()));
        long total = dsl.fetchCount(dsl.selectOne().from(TYPE).where(condition));
        var items = dsl.select(TYPE_ID, TYPE_CODE, TYPE_NAME, TYPE_SORT, TYPE_REMARK,
                TYPE_VERSION, TYPE_CREATED)
            .from(TYPE).where(condition)
            .orderBy(TYPE_SORT, TYPE_ID)
            .limit(query.pageSize()).offset(offset(query.page(), query.pageSize()))
            .fetch(row -> new DictionaryModels.DictionaryType(
                row.get(TYPE_ID), row.get(TYPE_CODE), row.get(TYPE_NAME), row.get(TYPE_SORT),
                row.get(TYPE_REMARK), row.get(TYPE_VERSION), row.get(TYPE_CREATED).toInstant()));
        return new DictionaryModels.Page<>(items, total);
    }

    @Override
    public List<DictionaryModels.TypeOption> findTypeOptions() {
        return dsl.select(TYPE_CODE, TYPE_NAME).from(TYPE)
            .where(TYPE_DELETED.isNull()).orderBy(TYPE_SORT, TYPE_ID)
            .fetch(row -> new DictionaryModels.TypeOption(row.get(TYPE_CODE), row.get(TYPE_NAME)));
    }

    @Override
    public DictionaryModels.Page<DictionaryModels.DictionaryData> findData(
        DictionaryCommands.DataQuery query) {
        Condition condition = TYPE_DELETED.isNull().and(DATA_DELETED.isNull())
            .and(TYPE_CODE.eq(query.dictType()));
        if (query.label() != null) condition = condition.and(DATA_LABEL.containsIgnoreCase(query.label()));
        if (query.value() != null) condition = condition.and(DATA_VALUE.containsIgnoreCase(query.value()));
        var base = DATA.join(TYPE).on(TYPE_ID.eq(DATA_TYPE_ID));
        long total = dsl.fetchCount(dsl.selectOne().from(base).where(condition));
        var items = dsl.select(DATA_ID, TYPE_CODE, DATA_LABEL, DATA_VALUE, DATA_COLOR,
                DATA_SORT, DATA_REMARK, DATA_VERSION, DATA_CREATED)
            .from(base).where(condition).orderBy(DATA_SORT, DATA_ID)
            .limit(query.pageSize()).offset(offset(query.page(), query.pageSize()))
            .fetch(row -> new DictionaryModels.DictionaryData(
                row.get(DATA_ID), row.get(TYPE_CODE), row.get(DATA_LABEL), row.get(DATA_VALUE),
                row.get(DATA_COLOR), row.get(DATA_SORT), row.get(DATA_REMARK),
                row.get(DATA_VERSION), row.get(DATA_CREATED).toInstant()));
        return new DictionaryModels.Page<>(items, total);
    }

    @Override
    public long currentRevision() {
        Long revision = dsl.select(REVISION_VALUE).from(REVISION)
            .where(REVISION_ID.eq((short) 1)).fetchOne(REVISION_VALUE);
        if (revision == null) throw new IllegalStateException("Dictionary catalog revision is missing");
        return revision;
    }

    @Override
    public Map<String, List<DictionaryModels.DisplayValue>> findDisplayValues(Set<String> types) {
        if (types.isEmpty()) return Map.of();
        var rows = dsl.select(TYPE_CODE, DATA_VALUE, DATA_LABEL, DATA_COLOR)
            .from(DATA.join(TYPE).on(TYPE_ID.eq(DATA_TYPE_ID)))
            .where(TYPE_DELETED.isNull().and(DATA_DELETED.isNull()).and(TYPE_CODE.in(types)))
            .orderBy(TYPE_CODE, DATA_SORT, DATA_ID)
            .limit(MAX_BATCH_VALUES + 1)
            .fetch();
        if (rows.size() > MAX_BATCH_VALUES) {
            throw new SystemDictionaryException.DataConflict(
                "Dictionary batch exceeds the maximum result size");
        }
        Map<String, List<DictionaryModels.DisplayValue>> mutable = new LinkedHashMap<>();
        rows.forEach(row -> mutable.computeIfAbsent(row.get(TYPE_CODE), ignored -> new java.util.ArrayList<>())
            .add(new DictionaryModels.DisplayValue(
                row.get(DATA_VALUE), row.get(DATA_LABEL), row.get(DATA_COLOR))));
        Map<String, List<DictionaryModels.DisplayValue>> result = new LinkedHashMap<>();
        mutable.forEach((type, values) -> result.put(type, List.copyOf(values)));
        return Map.copyOf(result);
    }

    @Override
    @Transactional
    public long createType(DictionaryModels.Actor actor, DictionaryCommands.CreateType command) {
        requireActor(actor, "dictionary:create");
        lockRevision();
        if (dsl.fetchCount(dsl.selectOne().from(TYPE).where(TYPE_DELETED.isNull())) >= MAX_LIVE_TYPES) {
            throw new SystemDictionaryException.DataConflict(
                "Dictionary catalog has reached the live type limit");
        }
        long id = nextId();
        try {
            dsl.insertInto(TYPE).set(TYPE_ID, id).set(TYPE_CODE, command.dictType())
                .set(TYPE_NAME, command.dictName()).set(TYPE_SORT, command.sort())
                .set(TYPE_REMARK, command.remark()).execute();
        } catch (IntegrityConstraintViolationException | DataIntegrityViolationException exception) {
            throw conflict("Dictionary type conflicts with current data", exception);
        }
        audit(actor, "DICTIONARY_TYPE", id, "CREATE", "dictionary:create");
        advanceRevision();
        return id;
    }

    @Override
    @Transactional
    public void updateType(DictionaryModels.Actor actor, long id, DictionaryCommands.UpdateType command) {
        requireActor(actor, "dictionary:update");
        lockRevision();
        int updated;
        try {
            updated = dsl.update(TYPE).set(TYPE_CODE, command.dictType()).set(TYPE_NAME, command.dictName())
                .set(TYPE_SORT, command.sort()).set(TYPE_REMARK, command.remark())
                .set(TYPE_UPDATED, DSL.currentOffsetDateTime())
                .set(TYPE_VERSION, TYPE_VERSION.plus(1L))
                .where(TYPE_ID.eq(id).and(TYPE_VERSION.eq(command.expectedVersion()))
                    .and(TYPE_DELETED.isNull())).execute();
        } catch (IntegrityConstraintViolationException | DataIntegrityViolationException exception) {
            throw conflict("Dictionary type conflicts with current data", exception);
        }
        requireUpdated(TYPE, TYPE_ID, TYPE_DELETED, id, updated);
        audit(actor, "DICTIONARY_TYPE", id, "UPDATE", "dictionary:update");
        advanceRevision();
    }

    @Override
    @Transactional
    public void deleteType(DictionaryModels.Actor actor, long id, long expectedVersion) {
        requireActor(actor, "dictionary:delete");
        lockRevision();
        Long version = dsl.select(TYPE_VERSION).from(TYPE)
            .where(TYPE_ID.eq(id).and(TYPE_DELETED.isNull())).forUpdate().fetchOne(TYPE_VERSION);
        if (version == null) throw notFound("Dictionary type");
        if (version != expectedVersion) throw new SystemDictionaryException.OptimisticLockConflict();
        if (dsl.fetchCount(dsl.selectOne().from(DATA)
            .where(DATA_TYPE_ID.eq(id).and(DATA_DELETED.isNull()))) > 0) {
            throw new SystemDictionaryException.DataConflict("Dictionary type still has live values");
        }
        dsl.update(TYPE).set(TYPE_DELETED, DSL.currentOffsetDateTime())
            .set(TYPE_UPDATED, DSL.currentOffsetDateTime()).set(TYPE_VERSION, TYPE_VERSION.plus(1L))
            .where(TYPE_ID.eq(id).and(TYPE_VERSION.eq(expectedVersion)).and(TYPE_DELETED.isNull()))
            .execute();
        audit(actor, "DICTIONARY_TYPE", id, "DELETE", "dictionary:delete");
        advanceRevision();
    }

    @Override
    @Transactional
    public long createData(DictionaryModels.Actor actor, DictionaryCommands.CreateData command) {
        requireActor(actor, "dictionary:update");
        lockRevision();
        long typeId = lockType(command.dictType());
        requireCapacity(typeId);
        long id = nextId();
        try {
            dsl.insertInto(DATA).set(DATA_ID, id).set(DATA_TYPE_ID, typeId)
                .set(DATA_LABEL, command.label()).set(DATA_VALUE, command.value())
                .set(DATA_COLOR, command.color()).set(DATA_SORT, command.sort())
                .set(DATA_REMARK, command.remark()).execute();
        } catch (IntegrityConstraintViolationException | DataIntegrityViolationException exception) {
            throw conflict("Dictionary value conflicts with current data", exception);
        }
        audit(actor, "DICTIONARY_DATA", id, "CREATE", "dictionary:update");
        advanceRevision();
        return id;
    }

    @Override
    @Transactional
    public void updateData(DictionaryModels.Actor actor, long id, DictionaryCommands.UpdateData command) {
        requireActor(actor, "dictionary:update");
        lockRevision();
        var current = dsl.select(DATA_TYPE_ID, DATA_VERSION).from(DATA)
            .where(DATA_ID.eq(id).and(DATA_DELETED.isNull())).forUpdate().fetchOne();
        if (current == null) throw notFound("Dictionary data");
        if (current.get(DATA_VERSION) != command.expectedVersion()) {
            throw new SystemDictionaryException.OptimisticLockConflict();
        }
        long targetTypeId = lockType(command.dictType());
        if (targetTypeId != current.get(DATA_TYPE_ID)) requireCapacity(targetTypeId);
        try {
            int updated = dsl.update(DATA).set(DATA_TYPE_ID, targetTypeId)
                .set(DATA_LABEL, command.label()).set(DATA_VALUE, command.value())
                .set(DATA_COLOR, command.color()).set(DATA_SORT, command.sort())
                .set(DATA_REMARK, command.remark()).set(DATA_UPDATED, DSL.currentOffsetDateTime())
                .set(DATA_VERSION, DATA_VERSION.plus(1L))
                .where(DATA_ID.eq(id).and(DATA_VERSION.eq(command.expectedVersion()))
                    .and(DATA_DELETED.isNull())).execute();
            if (updated != 1) throw new SystemDictionaryException.OptimisticLockConflict();
        } catch (IntegrityConstraintViolationException | DataIntegrityViolationException exception) {
            throw conflict("Dictionary value conflicts with current data", exception);
        }
        audit(actor, "DICTIONARY_DATA", id, "UPDATE", "dictionary:update");
        advanceRevision();
    }

    @Override
    @Transactional
    public void deleteData(DictionaryModels.Actor actor, long id, long expectedVersion) {
        requireActor(actor, "dictionary:update");
        lockRevision();
        int updated = dsl.update(DATA).set(DATA_DELETED, DSL.currentOffsetDateTime())
            .set(DATA_UPDATED, DSL.currentOffsetDateTime()).set(DATA_VERSION, DATA_VERSION.plus(1L))
            .where(DATA_ID.eq(id).and(DATA_VERSION.eq(expectedVersion)).and(DATA_DELETED.isNull()))
            .execute();
        requireUpdated(DATA, DATA_ID, DATA_DELETED, id, updated);
        audit(actor, "DICTIONARY_DATA", id, "DELETE", "dictionary:update");
        advanceRevision();
    }

    private void requireActor(DictionaryModels.Actor actor, String permissionCode) {
        if (!"PLATFORM".equals(actor.accountDomain())) throw new SecurityException("PLATFORM actor required");
        var versions = dsl.select(MEMBERSHIP_PERMISSION_VERSION, MEMBERSHIP_SESSION_VERSION)
            .from(MEMBERSHIP)
            .join(TENANT).on(TENANT_ID.eq(MEMBERSHIP_TENANT_ID))
            .join(USER).on(USER_ID.eq(MEMBERSHIP_USER_ID))
            .join(CREDENTIAL).on(CREDENTIAL_USER_ID.eq(USER_ID))
            .where(MEMBERSHIP_ID.eq(actor.membershipId())
                .and(MEMBERSHIP_TENANT_ID.eq(actor.tenantId()))
                .and(MEMBERSHIP_USER_ID.eq(actor.userId()))
                .and(MEMBERSHIP_DOMAIN.eq("PLATFORM"))
                .and(MEMBERSHIP_STATUS.eq(ACTIVE))
                .and(TENANT_DOMAIN.eq("PLATFORM")).and(TENANT_STATUS.eq(ACTIVE))
                .and(USER_DOMAIN.eq("PLATFORM")).and(USER_STATUS.eq(ACTIVE))
                .and(CREDENTIAL_DOMAIN.eq("PLATFORM")).and(CREDENTIAL_STATUS.eq(ACTIVE)))
            .forUpdate().of(TENANT, MEMBERSHIP, USER, CREDENTIAL).fetchOne();
        if (versions == null || !Objects.equals(versions.get(MEMBERSHIP_PERMISSION_VERSION),
                actor.permissionVersion())
            || !Objects.equals(versions.get(MEMBERSHIP_SESSION_VERSION), actor.sessionVersion())) {
            throw new SecurityException("Dictionary actor is stale or invalid");
        }
        Long grantId = dsl.select(GRANT_ID).from(MEMBERSHIP_ROLE)
            .join(ROLE).on(ROLE_ID.eq(MR_ROLE_ID).and(ROLE_TENANT_ID.eq(MR_TENANT_ID)))
            .join(GRANT).on(GRANT_ROLE_ID.eq(ROLE_ID).and(GRANT_TENANT_ID.eq(ROLE_TENANT_ID)))
            .join(PERMISSION).on(PERMISSION_ID.eq(GRANT_PERMISSION_ID))
            .join(DIMENSION).on(DIMENSION_GRANT_ID.eq(GRANT_ID))
            .where(MR_TENANT_ID.eq(actor.tenantId()).and(MR_MEMBERSHIP_ID.eq(actor.membershipId()))
                .and(ROLE_STATUS.eq(ACTIVE)).and(ROLE_DELETED.isNull())
                .and(GRANT_STATUS.eq(ACTIVE))
                .and(GRANT_VALID_FROM.isNull().or(GRANT_VALID_FROM.le(DSL.currentOffsetDateTime())))
                .and(GRANT_VALID_UNTIL.isNull())
                .and(PERMISSION_CODE.eq(permissionCode)).and(PERMISSION_STATUS.eq(ACTIVE))
                .and(DIMENSION_CODE.eq("TENANT")).and(DIMENSION_MODE.eq("TENANT_ALL"))
                .andNotExists(dsl.selectOne().from(DIMENSION.as("extra_dimension"))
                    .where(DSL.field(DSL.name("extra_dimension", "grant_id"), Long.class).eq(GRANT_ID)
                        .and(DSL.field(DSL.name("extra_dimension", "dimension_code"), String.class)
                            .ne("TENANT")))))
            .orderBy(GRANT_ID).limit(1)
            .forUpdate().of(GRANT, DIMENSION).fetchOne(GRANT_ID);
        if (grantId == null) throw new SecurityException("Dictionary permission is not currently granted");
    }

    private long lockType(String dictType) {
        Long id = dsl.select(TYPE_ID).from(TYPE)
            .where(TYPE_CODE.eq(dictType).and(TYPE_DELETED.isNull()))
            .forUpdate().fetchOne(TYPE_ID);
        if (id == null) throw notFound("Dictionary type");
        return id;
    }

    private void requireCapacity(long typeId) {
        int count = dsl.fetchCount(dsl.selectOne().from(DATA)
            .where(DATA_TYPE_ID.eq(typeId).and(DATA_DELETED.isNull())));
        if (count >= MAX_VALUES_PER_TYPE) {
            throw new SystemDictionaryException.DataConflict(
                "Dictionary type has reached the live value limit");
        }
    }

    private void advanceRevision() {
        int updated = dsl.update(REVISION).set(REVISION_VALUE, REVISION_VALUE.plus(1L))
            .set(REVISION_UPDATED, DSL.currentOffsetDateTime())
            .where(REVISION_ID.eq((short) 1)).execute();
        if (updated != 1) throw new IllegalStateException("Dictionary catalog revision is missing");
    }

    private void lockRevision() {
        Long revision = dsl.select(REVISION_VALUE).from(REVISION)
            .where(REVISION_ID.eq((short) 1)).forUpdate().fetchOne(REVISION_VALUE);
        if (revision == null) throw new IllegalStateException("Dictionary catalog revision is missing");
    }

    private void audit(DictionaryModels.Actor actor, String targetType, long targetId,
                       String action, String permissionCode) {
        dsl.insertInto(AUDIT).set(AUDIT_ID, nextId()).set(AUDIT_TENANT_ID, actor.tenantId())
            .set(AUDIT_OPERATOR, actor.membershipId()).set(AUDIT_TARGET_TYPE, targetType)
            .set(AUDIT_TARGET_REF, Long.toString(targetId)).set(AUDIT_ACTION, action)
            .set(AUDIT_DECISION, "ALLOW").set(AUDIT_REASON, "AUTHORIZED")
            .set(AUDIT_PERMISSION, permissionCode).set(AUDIT_AFTER, JSONB.valueOf("{}"))
            .set(AUDIT_TRACE, traceIdSupplier.get()).execute();
    }

    private long nextId() {
        var sequence = DSL.sequence(DSL.name("iam_id_seq"), Long.class);
        Field<Long> nextValue = sequence.nextval();
        Long value = dsl.select(nextValue).fetchOne(nextValue);
        if (value == null) throw new IllegalStateException("IAM sequence did not return an identifier");
        return value;
    }

    private <T> void requireUpdated(Table<Record> table, Field<Long> idField,
                                    Field<OffsetDateTime> deletedField, long id, int updated) {
        if (updated == 1) return;
        boolean exists = dsl.fetchExists(dsl.selectOne().from(table)
            .where(idField.eq(id).and(deletedField.isNull())));
        if (!exists) throw notFound("Dictionary record");
        throw new SystemDictionaryException.OptimisticLockConflict();
    }

    private static int offset(int page, int pageSize) {
        try {
            return Math.toIntExact(Math.multiplyExact((long) page - 1L, pageSize));
        } catch (ArithmeticException exception) {
            throw new SystemDictionaryException.InvalidRequest("Page offset is too large");
        }
    }

    private static SystemDictionaryException.NotFound notFound(String resource) {
        return new SystemDictionaryException.NotFound(resource + " was not found");
    }

    private static SystemDictionaryException.DataConflict conflict(String message, RuntimeException cause) {
        var conflict = new SystemDictionaryException.DataConflict(message);
        conflict.initCause(cause);
        return conflict;
    }

    private static <T> Field<T> field(Table<Record> table, String name, Class<T> type) {
        return DSL.field(DSL.name(table.getName(), name), type);
    }
}
