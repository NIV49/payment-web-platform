package com.niv.payment.merchant.persistence;

import com.niv.payment.merchant.core.AccountDomain;
import com.niv.payment.merchant.core.MerchantActor;
import com.niv.payment.merchant.core.MerchantCommand;
import com.niv.payment.merchant.core.MerchantDetail;
import com.niv.payment.merchant.core.MerchantDocumentMaintenance;
import com.niv.payment.merchant.core.MerchantException;
import com.niv.payment.merchant.core.MerchantLifecyclePolicy;
import com.niv.payment.merchant.core.MerchantModels;
import com.niv.payment.merchant.core.MerchantMutationResult;
import com.niv.payment.merchant.core.MerchantPage;
import com.niv.payment.merchant.core.MerchantQuery;
import com.niv.payment.merchant.core.MerchantRepository;
import com.niv.payment.merchant.core.MerchantStatus;
import com.niv.payment.merchant.core.MerchantOnboardingModels.Amendment;
import com.niv.payment.merchant.core.MerchantOnboardingModels.AmendmentDecisionView;
import com.niv.payment.merchant.core.MerchantOnboardingModels.AmendmentProfile;
import com.niv.payment.merchant.core.MerchantOnboardingModels.AmendmentRequest;
import com.niv.payment.merchant.core.MerchantOnboardingModels.AmendmentResult;
import com.niv.payment.merchant.core.MerchantOnboardingModels.AmendmentReviewRequest;
import com.niv.payment.merchant.core.MerchantOnboardingModels.CreateRequest;
import com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentContent;
import com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentKind;
import com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentMetadata;
import com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentUploadRequest;
import com.niv.payment.merchant.core.MerchantOnboardingModels.EligibleTenant;
import com.niv.payment.merchant.core.MerchantOnboardingModels.EligibleTenantPage;
import com.niv.payment.merchant.core.MerchantOnboardingModels.EligibleTenantQuery;
import com.niv.payment.merchant.core.MerchantOnboardingModels.Profile;
import com.niv.payment.merchant.core.MerchantOnboardingModels.SensitiveMode;
import com.niv.payment.merchant.core.ProfileUpdateCommand;
import com.niv.payment.merchant.core.RegistrationNumberPolicy;
import com.niv.payment.merchant.core.SubmissionCommand;
import com.niv.payment.merchant.core.TransitionCommand;
import com.niv.payment.merchant.core.crypto.IdempotencyDigest;
import com.niv.payment.merchant.core.crypto.MerchantCryptography;
import com.niv.payment.merchant.core.crypto.MerchantOnboardingCryptography;
import com.niv.payment.merchant.core.crypto.ProtectedRegistration;
import com.niv.payment.merchant.core.crypto.ProtectedPayload;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.exception.DataAccessException;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.Function;

import static com.niv.payment.permission.persistence.jooq.generated.Sequences.IAM_ID_SEQ;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.table;

/** PostgreSQL transaction boundary for Merchant lifecycle and its permanent command receipts. */
public final class JooqMerchantRepository implements MerchantRepository, MerchantDocumentMaintenance {
    private enum AmendmentDocumentMode { RETAIN, REPLACE }

    private record AmendmentDocumentReference(long documentId, AmendmentDocumentMode mode) { }

    private static final String ACTIVE = "ACTIVE";
    private static final String SEARCH_ALGORITHM = "HMAC-SHA-256";
    private static final String AEAD_ALGORITHM = "AES-256-GCM";
    private static final int COMMAND_SCHEMA_VERSION = 1;
    private static final int PROFILE_COMMAND_SCHEMA_VERSION = 2;
    private static final int ONBOARDING_COMMAND_SCHEMA_VERSION = 3;
    private static final int DIGEST_SCHEME_VERSION = 1;

    private static final Table<?> MERCHANT = table(name("merchant"));
    private static final Field<Long> ID = field(name("merchant", "id"), Long.class);
    private static final Field<Long> TENANT_ID = field(name("merchant", "tenant_id"), Long.class);
    private static final Field<String> CODE = field(name("merchant", "merchant_code"), String.class);
    private static final Field<String> LEGAL_NAME = field(name("merchant", "legal_name"), String.class);
    private static final Field<String> DISPLAY_NAME = field(name("merchant", "display_name"), String.class);
    private static final Field<String> MERCHANT_TYPE =
        field(name("merchant", "merchant_type_code"), String.class);
    private static final Field<String> LEGAL_PERSON =
        field(name("merchant", "legal_person_name"), String.class);
    private static final Field<String> AUTHENTICATION_TYPE =
        field(name("merchant", "authentication_type"), String.class);
    private static final Field<String> BRAND_NAME = field(name("merchant", "brand_name"), String.class);
    private static final Field<String> INDUSTRY_CODE = field(name("merchant", "industry_code"), String.class);
    private static final Field<String> REGISTERED_ADDRESS = field(name("merchant", "registered_address"), String.class);
    private static final Field<String> OPERATING_ADDRESS = field(name("merchant", "operating_address"), String.class);
    private static final Field<String> CONTACT_EMAIL = field(name("merchant", "contact_email"), String.class);
    private static final Field<String> CONTACT_PHONE = field(name("merchant", "contact_phone"), String.class);
    private static final Field<String> LEGAL_ID_TYPE = field(name("merchant", "legal_id_type_code"), String.class);
    private static final Field<String> LEGAL_ID_MASKED = field(name("merchant", "legal_id_no_masked"), String.class);
    private static final Field<LocalDate> LEGAL_ID_VALID_FROM = field(name("merchant", "legal_id_valid_from"), LocalDate.class);
    private static final Field<LocalDate> LEGAL_ID_VALID_TO = field(name("merchant", "legal_id_valid_to"), LocalDate.class);
    private static final Field<String> COUNTRY = field(name("merchant", "registration_country"), String.class);
    private static final Field<String> MASKED = field(name("merchant", "registration_number_masked"), String.class);
    private static final Field<byte[]> FINGERPRINT = field(name("merchant", "registration_fingerprint"), byte[].class);
    private static final Field<String> SEARCH_KEY = field(name("merchant", "registration_search_key_id"), String.class);
    private static final Field<String> FINGERPRINT_ALGORITHM = field(name("merchant", "registration_fingerprint_algorithm"), String.class);
    private static final Field<Integer> NORMALIZATION_VERSION = field(name("merchant", "registration_normalization_version"), Integer.class);
    private static final Field<byte[]> CIPHERTEXT = field(name("merchant", "registration_ciphertext"), byte[].class);
    private static final Field<byte[]> NONCE = field(name("merchant", "registration_nonce"), byte[].class);
    private static final Field<byte[]> AUTH_TAG = field(name("merchant", "registration_auth_tag"), byte[].class);
    private static final Field<String> AEAD_KEY = field(name("merchant", "registration_aead_key_id"), String.class);
    private static final Field<String> AEAD_ALGORITHM_FIELD = field(name("merchant", "registration_aead_algorithm"), String.class);
    private static final Field<String> STATUS = field(name("merchant", "status"), String.class);
    private static final Field<Long> ROW_VERSION = field(name("merchant", "row_version"), Long.class);
    private static final Field<OffsetDateTime> SUBMITTED_AT = field(name("merchant", "submitted_at"), OffsetDateTime.class);
    private static final Field<OffsetDateTime> REVIEWED_AT = field(name("merchant", "reviewed_at"), OffsetDateTime.class);
    private static final Field<String> LAST_DECISION = field(name("merchant", "last_decision"), String.class);
    private static final Field<String> LAST_REASON = field(name("merchant", "last_decision_reason_code"), String.class);
    private static final Field<String> REMARKS = field(name("merchant", "remarks"), String.class);
    private static final Field<String> STATUS_REASON = field(name("merchant", "status_reason_code"), String.class);
    private static final Field<Long> LAST_ACTOR = field(name("merchant", "last_decided_by_membership_id"), Long.class);
    private static final Field<OffsetDateTime> LAST_AT = field(name("merchant", "last_decided_at"), OffsetDateTime.class);
    private static final Field<OffsetDateTime> CREATED_AT = field(name("merchant", "created_at"), OffsetDateTime.class);
    private static final Field<OffsetDateTime> UPDATED_AT = field(name("merchant", "updated_at"), OffsetDateTime.class);
    private static final Table<?> OPERATING_MARKET = table(name("merchant_operating_market"));
    private static final Field<Long> MARKET_MERCHANT_ID =
        field(name("merchant_operating_market", "merchant_id"), Long.class);
    private static final Field<String> MARKET_CODE =
        field(name("merchant_operating_market", "market_code"), String.class);
    private static final Field<String> MARKET_STATUS =
        field(name("merchant_operating_market", "status"), String.class);

    private final DSLContext dsl;
    private final MerchantCryptography cryptography;
    private final MerchantOnboardingCryptography onboardingCryptography;
    private final String activeIdempotencyKeyId;
    private final Supplier<String> traceIdSupplier;

    public JooqMerchantRepository(
        DSLContext dsl,
        MerchantCryptography cryptography,
        MerchantOnboardingCryptography onboardingCryptography,
        String activeIdempotencyKeyId,
        Supplier<String> traceIdSupplier
    ) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.cryptography = Objects.requireNonNull(cryptography, "cryptography");
        this.onboardingCryptography = Objects.requireNonNull(onboardingCryptography,
            "onboardingCryptography");
        this.activeIdempotencyKeyId = requiredKeyId(activeIdempotencyKeyId);
        this.traceIdSupplier = Objects.requireNonNull(traceIdSupplier, "traceIdSupplier");
    }

    public JooqMerchantRepository(
        DSLContext dsl,
        MerchantCryptography cryptography,
        String activeIdempotencyKeyId,
        Supplier<String> traceIdSupplier
    ) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.cryptography = Objects.requireNonNull(cryptography, "cryptography");
        this.onboardingCryptography = null;
        this.activeIdempotencyKeyId = requiredKeyId(activeIdempotencyKeyId);
        this.traceIdSupplier = Objects.requireNonNull(traceIdSupplier, "traceIdSupplier");
    }

    @Override
    public Optional<MerchantDetail> findSelf(MerchantActor actor) {
        requireDomain(actor, AccountDomain.MERCHANT);
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            authorize(tx, actor, "merchant:self-view");
            Record row = tx.select(detailFields()).from(MERCHANT)
                .where(TENANT_ID.eq(actor.tenantId())).fetchOne();
            return Optional.ofNullable(row == null ? null
                : detail(row, marketCodes(tx, row.get(ID))));
        });
    }

    @Override
    public MerchantPage findPlatform(MerchantActor actor, MerchantQuery query) {
        requireDomain(actor, AccountDomain.PLATFORM);
        Objects.requireNonNull(query, "query");
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            authorize(tx, actor, "merchant:view");
            Condition condition = platformCondition(query);
            Long total = tx.select(count()).from(MERCHANT).where(condition).fetchOne(0, Long.class);
            int offset;
            try {
                offset = Math.multiplyExact(query.page() - 1, query.pageSize());
            } catch (ArithmeticException exception) {
                throw new MerchantException.InvalidRequest("Invalid merchant page");
            }
            var rows = tx.select(detailFields()).from(MERCHANT).where(condition)
                .orderBy(CREATED_AT.desc(), ID.desc()).limit(query.pageSize()).offset(offset).fetch();
            Map<Long, List<String>> markets = marketCodes(tx,
                rows.stream().map(row -> row.get(ID)).toList());
            List<MerchantDetail> items = rows.stream()
                .map(row -> detail(row, markets.getOrDefault(row.get(ID), List.of())))
                .toList();
            Set<Long> reviewPendingMerchantIds = items.stream()
                .filter(item -> item.status() == MerchantStatus.PENDING_REVIEW)
                .map(MerchantDetail::merchantId)
                .collect(java.util.stream.Collectors.toSet());
            List<Long> merchantIds = items.stream().map(MerchantDetail::merchantId).toList();
            if (!merchantIds.isEmpty()) {
                Table<?> amendment = table(name("merchant_amendment"));
                Field<Long> amendmentMerchantId = field(
                    name("merchant_amendment", "merchant_id"), Long.class);
                Field<String> amendmentStatus = field(
                    name("merchant_amendment", "status"), String.class);
                reviewPendingMerchantIds.addAll(tx.selectDistinct(amendmentMerchantId)
                    .from(amendment)
                    .where(amendmentMerchantId.in(merchantIds)
                        .and(amendmentStatus.eq("PENDING_REVIEW")))
                    .fetch(amendmentMerchantId));
            }
            return new MerchantPage(items, total == null ? 0 : total,
                reviewPendingMerchantIds);
        });
    }

    @Override
    public MerchantDetail findPlatformDetail(MerchantActor actor, long merchantId) {
        requireDomain(actor, AccountDomain.PLATFORM);
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            authorize(tx, actor, "merchant:view");
            Record row = tx.select(detailFields()).from(MERCHANT)
                .where(ID.eq(merchantId)).fetchOne();
            if (row == null) throw new MerchantException.ResourceNotFound();
            return effectiveDetail(tx, row, marketCodes(tx, merchantId));
        });
    }

    @Override
    public MerchantMutationResult submit(MerchantActor actor, SubmissionCommand command) {
        requireDomain(actor, AccountDomain.MERCHANT);
        Objects.requireNonNull(command, "command");
        try {
            return dsl.transactionResult(configuration -> submit(configuration.dsl(), actor, command));
        } catch (DataAccessException exception) {
            throw mapConflict(exception);
        }
    }

    @Override
    public MerchantMutationResult transition(MerchantActor actor, TransitionCommand command) {
        requireDomain(actor, AccountDomain.PLATFORM);
        Objects.requireNonNull(command, "command");
        try {
            return dsl.transactionResult(configuration -> transition(configuration.dsl(), actor, command));
        } catch (DataAccessException exception) {
            throw mapConflict(exception);
        }
    }

    @Override
    public MerchantMutationResult updateProfile(MerchantActor actor, ProfileUpdateCommand command) {
        requireDomain(actor, AccountDomain.PLATFORM);
        Objects.requireNonNull(command, "command");
        try {
            return dsl.transactionResult(configuration ->
                updateProfile(configuration.dsl(), actor, command));
        } catch (DataAccessException exception) {
            throw mapConflict(exception);
        }
    }

    @Override
    public EligibleTenantPage findEligibleTenants(MerchantActor actor, EligibleTenantQuery query) {
        requireDomain(actor, AccountDomain.PLATFORM);
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            authorize(tx, actor, MerchantCommand.CREATE.permission());
            String code = escapedLike(query.tenantCode());
            String label = escapedLike(query.tenantName());
            Condition condition = field(name("tenant", "account_domain"), String.class).eq("MERCHANT")
                .and(field(name("tenant", "status"), String.class).eq(ACTIVE))
                .andNotExists(selectOne().from(MERCHANT)
                    .where(TENANT_ID.eq(field(name("tenant", "id"), Long.class))));
            if (code != null) condition = condition.and(
                field(name("tenant", "tenant_code"), String.class).lower().like(code, '\\'));
            if (label != null) condition = condition.and(
                field(name("tenant", "tenant_name"), String.class).lower().like(label, '\\'));
            Table<?> tenant = table(name("iam_tenant")).as("tenant");
            Long total = tx.select(count()).from(tenant).where(condition).fetchOne(0, Long.class);
            int offset;
            try {
                offset = Math.multiplyExact(query.page() - 1, query.pageSize());
            } catch (ArithmeticException exception) {
                throw new MerchantException.InvalidRequest("Invalid eligible Tenant page");
            }
            List<EligibleTenant> items = tx.select(
                    field(name("tenant", "id"), Long.class),
                    field(name("tenant", "tenant_code"), String.class),
                    field(name("tenant", "tenant_name"), String.class))
                .from(tenant).where(condition)
                .orderBy(field(name("tenant", "tenant_code")), field(name("tenant", "id")))
                .limit(query.pageSize()).offset(offset)
                .fetch(row -> new EligibleTenant(row.get(0, Long.class), row.get(1, String.class),
                    row.get(2, String.class)));
            return new EligibleTenantPage(items, total == null ? 0 : total);
        });
    }

    @Override
    public DocumentMetadata uploadDocument(MerchantActor actor, DocumentUploadRequest request) {
        requireDomain(actor, AccountDomain.PLATFORM);
        requireOnboardingCryptography();
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            authorize(tx, actor, MerchantCommand.DOCUMENT_UPLOAD.permission());
            lockProtectionFences(tx, false, false, true);
            requireEligibleTarget(tx, request.targetTenantId(), false);
            long documentId = nextId(tx);
            OffsetDateTime now = databaseNow(tx);
            String keyId = activeProtectionKey(tx, "DOCUMENT_AEAD");
            byte[] content = request.normalizedContent();
            ProtectedPayload protectedContent = onboardingCryptography.protectDocument(
                documentId, request.targetTenantId(), actor.membershipId(), request.kind(),
                request.mediaType(), request.width(), request.height(), content.length, content, keyId);
            allocateNonce(tx, "DOCUMENT_AEAD", protectedContent);
            tx.execute("""
                INSERT INTO merchant_document(
                  id,target_tenant_id,actor_tenant_id,actor_membership_id,kind,media_type,
                  width,height,size_bytes,ciphertext,nonce,auth_tag,aead_key_id,aead_algorithm,
                  aad_scheme_version,protection_version,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS timestamptz))
                """, documentId, request.targetTenantId(), actor.tenantId(), actor.membershipId(),
                request.kind().name(), request.mediaType(), request.width(), request.height(),
                content.length, protectedContent.ciphertext(), protectedContent.nonce(),
                protectedContent.authenticationTag(), protectedContent.keyId(),
                protectedContent.algorithm(), protectedContent.aadSchemeVersion(),
                protectedContent.protectionVersion(), now.plusMinutes(30));
            Arrays.fill(content, (byte) 0);
            return new DocumentMetadata(documentId, request.targetTenantId(), request.kind(),
                request.mediaType(), request.width(), request.height(),
                request.normalizedContent().length, now.plusMinutes(30), false);
        });
    }

    @Override
    public DocumentContent readStagedDocument(MerchantActor actor, long documentId) {
        requireDomain(actor, AccountDomain.PLATFORM);
        requireOnboardingCryptography();
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            authorize(tx, actor, MerchantCommand.DOCUMENT_UPLOAD.permission());
            Record row = tx.fetchOne("""
                SELECT target_tenant_id,actor_membership_id,kind,media_type,width,height,size_bytes,
                       ciphertext,nonce,auth_tag,aead_key_id,aead_algorithm,aad_scheme_version,
                       protection_version
                  FROM merchant_document
                 WHERE id=? AND actor_tenant_id=? AND actor_membership_id=?
                   AND attachment_scope='TEMPORARY' AND deleted_at IS NULL
                   AND expires_at>statement_timestamp()
                """, documentId, actor.tenantId(), actor.membershipId());
            if (row == null) throw new MerchantException.ResourceNotFound();
            return decryptDocument(documentId, row, 0);
        });
    }

    @Override
    public void deleteDocument(MerchantActor actor, long documentId) {
        requireDomain(actor, AccountDomain.PLATFORM);
        dsl.transaction(configuration -> {
            DSLContext tx = configuration.dsl();
            authorize(tx, actor, MerchantCommand.DOCUMENT_UPLOAD.permission());
            lockProtectionFences(tx, false, false, true);
            int changed = tx.fetch("""
                DELETE FROM merchant_document
                 WHERE id=? AND actor_tenant_id=? AND actor_membership_id=?
                   AND attachment_scope='TEMPORARY' AND deleted_at IS NULL
                RETURNING id
                """, documentId, actor.tenantId(), actor.membershipId()).size();
            if (changed != 1) throw new MerchantException.ResourceNotFound();
        });
    }

    @Override
    public int deleteExpiredTemporaryDocuments(int batchSize) {
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            lockProtectionFences(tx, false, false, true);
            return tx.fetch("""
                WITH candidates AS (
                    SELECT id
                      FROM merchant_document
                     WHERE attachment_scope='TEMPORARY'
                       AND deleted_at IS NULL
                       AND expires_at<=statement_timestamp()
                     ORDER BY expires_at,id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                )
                DELETE FROM merchant_document document
                 USING candidates
                 WHERE document.id=candidates.id
                   AND document.attachment_scope='TEMPORARY'
                   AND document.deleted_at IS NULL
                RETURNING document.id
                """, batchSize).size();
        });
    }

    @Override
    public DocumentContent readMerchantDocument(
        MerchantActor actor, long merchantId, DocumentKind kind, Long amendmentId
    ) {
        requireDomain(actor, AccountDomain.PLATFORM);
        requireOnboardingCryptography();
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            authorize(tx, actor, MerchantCommand.DOCUMENT_VIEW.permission());
            List<Record> rows = amendmentId == null ? tx.fetch("""
                    SELECT document.id,document.target_tenant_id,document.actor_membership_id,
                           document.kind,document.media_type,document.width,document.height,
                           document.size_bytes,document.ciphertext,document.nonce,document.auth_tag,
                           document.aead_key_id,document.aead_algorithm,document.aad_scheme_version,
                           document.protection_version
                      FROM merchant_document_binding binding
                      JOIN merchant_document document ON document.id=binding.document_id
                      JOIN merchant merchant_row ON merchant_row.id=binding.merchant_id
                     WHERE binding.merchant_id=? AND binding.kind=?
                       AND document.attachment_scope='MERCHANT' AND document.deleted_at IS NULL
                       AND document.merchant_id=merchant_row.id
                       AND document.target_tenant_id=merchant_row.tenant_id
                     LIMIT 2
                    """, merchantId, kind.name()) : tx.fetch("""
                    SELECT document.id,document.target_tenant_id,document.actor_membership_id,
                           document.kind,document.media_type,document.width,document.height,
                           document.size_bytes,document.ciphertext,document.nonce,document.auth_tag,
                           document.aead_key_id,document.aead_algorithm,document.aad_scheme_version,
                           document.protection_version
                      FROM merchant_amendment amendment
                      JOIN merchant merchant_row ON merchant_row.id=amendment.merchant_id
                      JOIN merchant_amendment_document reference
                        ON reference.amendment_id=amendment.id
                       AND reference.kind=?
                      JOIN merchant_document document
                        ON document.id=reference.document_id
                       AND document.kind=reference.kind
                       AND document.target_tenant_id=amendment.target_tenant_id
                      LEFT JOIN merchant_document_binding binding
                        ON binding.merchant_id=amendment.merchant_id
                       AND binding.kind=reference.kind
                       AND binding.document_id=document.id
                     WHERE amendment.id=? AND amendment.merchant_id=?
                       AND amendment.target_tenant_id=merchant_row.tenant_id
                       AND amendment.status='PENDING_REVIEW'
                       AND document.deleted_at IS NULL
                       AND (
                         (reference.document_mode='REPLACE'
                           AND document.attachment_scope='AMENDMENT'
                           AND document.amendment_id=amendment.id
                           AND document.merchant_id=amendment.merchant_id)
                         OR
                         (reference.document_mode='RETAIN'
                           AND document.attachment_scope='MERCHANT'
                           AND document.merchant_id=amendment.merchant_id
                           AND binding.document_id IS NOT NULL)
                       )
                     LIMIT 2
                    """, kind.name(), amendmentId, merchantId);
            if (rows.size() != 1) throw new MerchantException.ResourceNotFound();
            Record row = rows.getFirst();
            return decryptDocument(row.get(0, Long.class), row, 1);
        });
    }

    @Override
    public MerchantMutationResult create(MerchantActor actor, CreateRequest request) {
        requireDomain(actor, AccountDomain.PLATFORM);
        requireOnboardingCryptography();
        try {
            return dsl.transactionResult(configuration -> create(configuration.dsl(), actor, request));
        } catch (DataAccessException exception) {
            throw mapConflict(exception);
        }
    }

    @Override
    public AmendmentResult createAmendment(MerchantActor actor, AmendmentRequest request) {
        requireDomain(actor, AccountDomain.PLATFORM);
        requireOnboardingCryptography();
        try {
            return dsl.transactionResult(configuration ->
                createAmendment(configuration.dsl(), actor, request));
        } catch (DataAccessException exception) {
            throw mapConflict(exception);
        }
    }

    @Override
    public Amendment findPendingAmendment(MerchantActor actor, long merchantId) {
        requireDomain(actor, AccountDomain.PLATFORM);
        return dsl.transactionResult(configuration ->
            findPendingAmendment(configuration.dsl(), actor, merchantId));
    }

    @Override
    public AmendmentResult reviewAmendment(
        MerchantActor actor, AmendmentReviewRequest request
    ) {
        requireDomain(actor, AccountDomain.PLATFORM);
        requireOnboardingCryptography();
        AmendmentDecisionOutcome outcome;
        try {
            outcome = dsl.transactionResult(configuration ->
                reviewAmendment(configuration.dsl(), actor, request));
        } catch (DataAccessException exception) {
            throw mapConflict(exception);
        }
        if (outcome.stale()) throw new MerchantException.OptimisticLockConflict();
        return outcome.result();
    }

    private MerchantMutationResult submit(DSLContext tx, MerchantActor actor, SubmissionCommand command) {
        authorize(tx, actor, command.command().permission());
        lockIdempotencyKey(tx, actor, command.command(), command.idempotencyKey());
        MerchantMutationResult replay = replay(tx, actor, command.command(), command.idempotencyKey(),
            keyId -> submissionDigest(command, keyId, DIGEST_SCHEME_VERSION));
        if (replay != null) return replay;
        lockProtectionFences(tx, true, false, false);
        IdempotencyDigest digest = submissionDigest(command, activeIdempotencyKeyId, DIGEST_SCHEME_VERSION);

        Record current = tx.select(detailFields()).from(MERCHANT)
            .where(TENANT_ID.eq(actor.tenantId())).forUpdate().fetchOne();
        if (command.command() == MerchantCommand.SUBMIT) {
            if (current != null) throw new MerchantException.StateConflict();
            return insertSubmission(tx, actor, command, digest);
        }
        if (current == null) throw new MerchantException.ResourceNotFound();
        return updateResubmission(tx, actor, command, digest, current);
    }

    private MerchantMutationResult create(DSLContext tx, MerchantActor actor, CreateRequest request) {
        MerchantCommand operation = MerchantCommand.CREATE;
        authorize(tx, actor, operation.permission());
        lockIdempotencyKey(tx, actor, operation, request.idempotencyKey());
        MerchantMutationResult replay = replay(tx, actor, operation, request.idempotencyKey(),
            ONBOARDING_COMMAND_SCHEMA_VERSION,
            keyId -> onboardingDigest(operation.name(), request.targetTenantId(), null,
                request.profile(), keyId));
        if (replay != null) return replay;
        lockProtectionFences(tx, true, true, true);
        requireEligibleTarget(tx, request.targetTenantId(), true);

        long merchantId = nextId(tx);
        OffsetDateTime now = databaseNow(tx);
        Profile profile = request.profile();
        ProtectedRegistration registration = protect(tx, merchantId, request.targetTenantId(),
            profile.registrationCountry(), profile.registrationNumber().value());
        ProtectedPayload legalId = onboardingCryptography.protectLegalId(
            merchantId, request.targetTenantId(), profile.legalIdTypeCode(),
            profile.legalIdNo().value(), activeProtectionKey(tx, "LEGAL_ID_AEAD"));
        allocateNonce(tx, "LEGAL_ID_AEAD", legalId);
        Map<DocumentKind, Long> documents = documentIds(profile);
        lockAttachableDocuments(tx, actor, request.targetTenantId(), documents);
        String merchantCode = merchantCode(merchantId);
        tx.execute("""
            INSERT INTO merchant(
              id,tenant_id,account_domain,merchant_code,legal_name,display_name,brand_name,
              merchant_type_code,industry_code,legal_person_name,authentication_type,
              registration_country,registration_number_masked,registration_fingerprint,
              registration_search_key_id,registration_fingerprint_algorithm,
              registration_normalization_version,registration_ciphertext,registration_nonce,
              registration_auth_tag,registration_aead_key_id,registration_aead_algorithm,
              registered_address,operating_address,contact_email,contact_phone,legal_id_type_code,
              legal_id_no_masked,legal_id_ciphertext,legal_id_nonce,legal_id_auth_tag,
              legal_id_aead_key_id,legal_id_aead_algorithm,legal_id_aad_scheme_version,
              legal_id_protection_version,
              legal_id_valid_from,legal_id_valid_to,remarks,status,status_reason_code,row_version,
              submitted_at,application_source,application_actor_tenant_id,
              application_author_membership_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,
                    ?,?,?,0,CAST(? AS timestamptz),?,?,?)
            """, merchantId, request.targetTenantId(), AccountDomain.MERCHANT.name(), merchantCode,
            profile.legalName(), profile.displayName(), profile.brandName(),
            profile.merchantTypeCode(), profile.industryCode(), profile.legalPersonName(),
            profile.authenticationType(), profile.registrationCountry(), registration.masked(),
            registration.fingerprint().value(), registration.fingerprint().searchKeyId(),
            registration.fingerprint().algorithm(), registration.normalizationVersion(),
            registration.ciphertext(), registration.nonce(), registration.authenticationTag(),
            registration.aeadKeyId(), registration.algorithm(), profile.registeredAddress(),
            profile.operatingAddress(), profile.contactEmail(), profile.contactPhone(),
            profile.legalIdTypeCode(), legalId.masked(), legalId.ciphertext(), legalId.nonce(),
            legalId.authenticationTag(), legalId.keyId(), legalId.algorithm(),
            legalId.aadSchemeVersion(), legalId.protectionVersion(),
            profile.legalIdValidity().validFrom(),
            profile.legalIdValidity().validTo(), profile.remarks(),
            MerchantStatus.PENDING_REVIEW.name(), "PLATFORM_APPLICATION_SUBMITTED", now,
            AccountDomain.PLATFORM.name(), actor.tenantId(), actor.membershipId());
        replaceMarkets(tx, merchantId, request.targetTenantId(), profile.marketCodes(), now);
        attachDocumentsToMerchant(tx, merchantId, documents, now);
        MerchantMutationResult result = new MerchantMutationResult(
            merchantId, merchantCode, MerchantStatus.PENDING_REVIEW, 0);
        IdempotencyDigest digest = onboardingDigest(operation.name(), request.targetTenantId(),
            null, profile, activeIdempotencyKeyId);
        audit(tx, actor, result, null, "PLATFORM_APPLICATION_SUBMITTED", fullProfileFields());
        receipt(tx, actor, operation, request.idempotencyKey(), digest, result,
            RegistrationNumberPolicy.NORMALIZATION_VERSION, ONBOARDING_COMMAND_SCHEMA_VERSION);
        return result;
    }

    private AmendmentResult createAmendment(
        DSLContext tx, MerchantActor actor, AmendmentRequest request
    ) {
        authorize(tx, actor, MerchantCommand.AMEND.permission());
        lockAmendmentIdempotencyKey(tx, actor, "AMEND", request.idempotencyKey());
        AmendmentResult replay = replayAmendment(tx, actor, "AMEND", request.idempotencyKey(),
            keyId -> onboardingDigest("AMEND", request.merchantId(), request.expectedVersion(),
                request.profile(), keyId));
        if (replay != null) return replay;
        lockProtectionFences(tx, true, true, true);
        Record current = tx.fetchOne("""
            SELECT id,tenant_id,merchant_code,status,row_version,registration_country,
                   registration_number_masked,registration_fingerprint,
                   registration_search_key_id,registration_fingerprint_algorithm,
                   registration_normalization_version,registration_ciphertext,registration_nonce,
                   registration_auth_tag,registration_aead_key_id,registration_aead_algorithm,
                   legal_id_type_code,legal_id_no_masked,legal_id_ciphertext,legal_id_nonce,
                   legal_id_auth_tag,legal_id_aead_key_id,legal_id_aead_algorithm,
                   legal_id_aad_scheme_version,legal_id_protection_version
              FROM merchant WHERE id=? FOR UPDATE
            """, request.merchantId());
        if (current == null) throw new MerchantException.ResourceNotFound();
        MerchantStatus originStatus = MerchantStatus.valueOf(current.get(3, String.class));
        if (originStatus != MerchantStatus.ACTIVE && originStatus != MerchantStatus.DISABLED) {
            throw new MerchantException.StateConflict();
        }
        long originVersion = current.get(4, Long.class);
        if (originVersion != request.expectedVersion()) {
            throw new MerchantException.OptimisticLockConflict();
        }
        if (tx.fetchExists(table(name("merchant_amendment")),
            field(name("merchant_id"), Long.class).eq(request.merchantId())
                .and(field(name("status"), String.class).eq("PENDING_REVIEW")))) {
            throw new MerchantException.AmendmentAlreadyPending();
        }
        Profile profile = request.profile();
        boolean replaceRegistration = profile.registrationNumber().mode() == SensitiveMode.REPLACE;
        boolean replaceLegalId = profile.legalIdNo().mode() == SensitiveMode.REPLACE;
        if (!replaceRegistration && (!Objects.equals(current.get(5, String.class),
            profile.registrationCountry()) || current.get(11, byte[].class) == null)) {
            throw new MerchantException.InvalidRequest(
                "registrationNumber REPLACE is required for this amendment");
        }
        if (!replaceLegalId && (!Objects.equals(current.get(16, String.class),
            profile.legalIdTypeCode()) || current.get(18, byte[].class) == null)) {
            throw new MerchantException.InvalidRequest("legalIdNo REPLACE is required for this amendment");
        }
        long amendmentId = nextId(tx);
        long tenantId = current.get(1, Long.class);
        ProtectedRegistration registration = replaceRegistration
            ? protect(tx, request.merchantId(), tenantId, profile.registrationCountry(),
                profile.registrationNumber().value()) : registrationFrom(current, 6);
        if (replaceRegistration) {
            requireRegistrationAvailable(tx, request.merchantId(), profile.registrationCountry(),
                registration.fingerprint().value());
        }
        ProtectedPayload legalId = replaceLegalId
            ? onboardingCryptography.protectLegalId(request.merchantId(), tenantId,
                profile.legalIdTypeCode(), profile.legalIdNo().value(),
                activeProtectionKey(tx, "LEGAL_ID_AEAD")) : legalIdFrom(current, 17);
        if (replaceLegalId) allocateNonce(tx, "LEGAL_ID_AEAD", legalId);
        Map<DocumentKind, Long> documents = documentIds(profile);
        Map<DocumentKind, AmendmentDocumentReference> documentReferences =
            lockAmendmentDocuments(tx, actor, request.merchantId(), tenantId, documents);
        OffsetDateTime now = databaseNow(tx);
        tx.execute("""
            INSERT INTO merchant_amendment(
              id,merchant_id,target_tenant_id,status,row_version,origin_merchant_version,
              origin_status,author_tenant_id,author_membership_id,display_name,brand_name,
              authentication_type,merchant_type_code,industry_code,legal_name,
              registration_country,registered_address,operating_address,legal_person_name,
              contact_email,contact_phone,legal_id_type_code,legal_id_valid_from,legal_id_valid_to,
              remarks,registration_mode,registration_number_masked,registration_fingerprint,
              registration_search_key_id,registration_fingerprint_algorithm,
              registration_normalization_version,registration_ciphertext,registration_nonce,
              registration_auth_tag,registration_aead_key_id,registration_aead_algorithm,
              legal_id_mode,legal_id_no_masked,legal_id_ciphertext,legal_id_nonce,legal_id_auth_tag,
              legal_id_aead_key_id,legal_id_aead_algorithm,legal_id_aad_scheme_version,
              legal_id_protection_version,created_at,updated_at)
            VALUES (?,?,?,'PENDING_REVIEW',0,
                    ?,?,?,?,?, ?,?,?,?,?, ?,?,?,?,?, ?,?,?,?,?, ?,?,?,?,?,
                    ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS timestamptz),CAST(? AS timestamptz))
            """, amendmentId, request.merchantId(), tenantId, originVersion, originStatus.name(),
            actor.tenantId(), actor.membershipId(), profile.displayName(), profile.brandName(),
            profile.authenticationType(), profile.merchantTypeCode(), profile.industryCode(),
            profile.legalName(), profile.registrationCountry(), profile.registeredAddress(),
            profile.operatingAddress(), profile.legalPersonName(), profile.contactEmail(),
            profile.contactPhone(), profile.legalIdTypeCode(), profile.legalIdValidity().validFrom(),
            profile.legalIdValidity().validTo(), profile.remarks(),
            profile.registrationNumber().mode().name(), registration.masked(),
            registration.fingerprint().value(), registration.fingerprint().searchKeyId(),
            registration.fingerprint().algorithm(), registration.normalizationVersion(),
            registration.ciphertext(), registration.nonce(), registration.authenticationTag(),
            registration.aeadKeyId(), registration.algorithm(), profile.legalIdNo().mode().name(),
            legalId.masked(), legalId.ciphertext(), legalId.nonce(), legalId.authenticationTag(),
            legalId.keyId(), legalId.algorithm(), legalId.aadSchemeVersion(),
            legalId.protectionVersion(), now, now);
        for (String market : profile.marketCodes()) {
            tx.execute("INSERT INTO merchant_amendment_market(amendment_id,market_code) VALUES (?,?)",
                amendmentId, market);
        }
        attachDocumentsToAmendment(
            tx, request.merchantId(), amendmentId, documentReferences, now);
        AmendmentResult result = new AmendmentResult(amendmentId, request.merchantId(),
            "PENDING_REVIEW", 0, originStatus, originVersion, originVersion, originStatus, now);
        amendmentAudit(tx, actor, result, null, "AMEND", "PLATFORM_AMENDMENT_SUBMITTED");
        amendmentReceipt(tx, actor, "AMEND", request.idempotencyKey(),
            onboardingDigest("AMEND", request.merchantId(), request.expectedVersion(), profile,
                activeIdempotencyKeyId), result, MerchantCommand.AMEND.permission());
        return result;
    }

    private Amendment findPendingAmendment(DSLContext tx, MerchantActor actor, long merchantId) {
        authorize(tx, actor, "merchant:view");
        if (!tx.fetchExists(selectOne().from(MERCHANT).where(ID.eq(merchantId)))) {
            throw new MerchantException.ResourceNotFound();
        }
        Record row = tx.fetchOne("""
            SELECT * FROM merchant_amendment
             WHERE merchant_id=? AND status='PENDING_REVIEW'
            """, merchantId);
        if (row == null) return null;
        long amendmentId = row.get("id", Long.class);
        Map<DocumentKind, DocumentMetadata> documents = amendmentDocuments(tx, amendmentId);
        List<String> markets = tx.fetch(
            "SELECT market_code FROM merchant_amendment_market WHERE amendment_id=? ORDER BY market_code",
            amendmentId).getValues(0, String.class);
        if (documents.size() != DocumentKind.values().length) {
            throw new MerchantException.ProtectedFieldUnavailable();
        }
        AmendmentProfile profile = new AmendmentProfile(
            row.get("display_name", String.class), row.get("brand_name", String.class),
            row.get("authentication_type", String.class), row.get("merchant_type_code", String.class),
            row.get("industry_code", String.class), documents.get(DocumentKind.BRAND_LOGO),
            row.get("legal_name", String.class), row.get("registration_country", String.class),
            row.get("registration_number_masked", String.class), markets,
            row.get("registered_address", String.class), row.get("operating_address", String.class),
            documents.get(DocumentKind.BUSINESS_LICENSE), row.get("legal_person_name", String.class),
            row.get("contact_email", String.class), row.get("contact_phone", String.class),
            row.get("legal_id_type_code", String.class), row.get("legal_id_no_masked", String.class),
            new com.niv.payment.merchant.core.MerchantOnboardingModels.LegalIdValidity(
                row.get("legal_id_valid_from", LocalDate.class),
                row.get("legal_id_valid_to", LocalDate.class)),
            documents.get(DocumentKind.LEGAL_ID_FRONT), documents.get(DocumentKind.LEGAL_ID_BACK),
            documents.get(DocumentKind.LEGAL_ID_HOLDING), row.get("remarks", String.class));
        AmendmentDecisionView decision = row.get("decision", String.class) == null ? null
            : new AmendmentDecisionView(
                com.niv.payment.merchant.core.MerchantOnboardingModels.AmendmentDecision.valueOf(
                    row.get("decision", String.class)),
                row.get("decision_reason_code", String.class), row.get("decided_at", OffsetDateTime.class));
        return new Amendment(amendmentId, merchantId, row.get("status", String.class),
            row.get("row_version", Long.class), row.get("origin_merchant_version", Long.class),
            MerchantStatus.valueOf(row.get("origin_status", String.class)),
            row.get("author_membership_id", Long.class),
            !(Objects.equals(row.get("author_tenant_id", Long.class), actor.tenantId())
                && Objects.equals(row.get("author_membership_id", Long.class), actor.membershipId())),
            profile, decision, row.get("created_at", OffsetDateTime.class),
            row.get("updated_at", OffsetDateTime.class));
    }

    private AmendmentDecisionOutcome reviewAmendment(
        DSLContext tx, MerchantActor actor, AmendmentReviewRequest request
    ) {
        authorize(tx, actor, "merchant:review");
        Record author = tx.fetchOne("""
            SELECT author_tenant_id,author_membership_id FROM merchant_amendment
             WHERE id=? AND merchant_id=?
            """, request.amendmentId(), request.merchantId());
        if (author == null) throw new MerchantException.ResourceNotFound();
        if (Objects.equals(author.get(0, Long.class), actor.tenantId())
            && Objects.equals(author.get(1, Long.class), actor.membershipId())) {
            throw new MerchantException.PermissionDenied();
        }
        lockAmendmentIdempotencyKey(tx, actor, "REVIEW", request.idempotencyKey());
        AmendmentResult replay = replayAmendmentReview(tx, actor, request);
        if (replay != null) return new AmendmentDecisionOutcome("STALE".equals(replay.status()), replay);
        lockProtectionFences(tx, true, true, true);
        Record amendment = tx.fetchOne("""
            SELECT * FROM merchant_amendment
             WHERE id=? AND merchant_id=? FOR UPDATE
            """, request.amendmentId(), request.merchantId());
        if (amendment == null) throw new MerchantException.ResourceNotFound();
        if (Objects.equals(amendment.get("author_tenant_id", Long.class), actor.tenantId())
            && Objects.equals(amendment.get("author_membership_id", Long.class), actor.membershipId())) {
            throw new MerchantException.PermissionDenied();
        }
        if (!"PENDING_REVIEW".equals(amendment.get("status", String.class))) {
            throw new MerchantException.StateConflict();
        }
        long amendmentVersion = amendment.get("row_version", Long.class);
        if (amendmentVersion != request.expectedVersion()) {
            throw new MerchantException.OptimisticLockConflict();
        }
        Record merchant = tx.fetchOne("SELECT * FROM merchant WHERE id=? FOR UPDATE", request.merchantId());
        if (merchant == null) throw new MerchantException.ResourceNotFound();
        MerchantStatus merchantStatus = MerchantStatus.valueOf(merchant.get("status", String.class));
        long merchantVersion = merchant.get("row_version", Long.class);
        long originVersion = amendment.get("origin_merchant_version", Long.class);
        MerchantStatus originStatus = MerchantStatus.valueOf(amendment.get("origin_status", String.class));
        OffsetDateTime now = databaseNow(tx);
        if (merchantVersion != originVersion || merchantStatus != originStatus) {
            tx.execute("""
                UPDATE merchant_amendment
                   SET status='STALE',row_version=row_version+1,decision=?,
                       decision_reason_code='PLATFORM_AMENDMENT_STALE',decided_by_tenant_id=?,
                       decided_by_membership_id=?,decided_at=CAST(? AS timestamptz),
                       updated_at=CAST(? AS timestamptz)
                 WHERE id=? AND row_version=? AND status='PENDING_REVIEW'
                """, request.decision().name(), actor.tenantId(), actor.membershipId(), now, now,
                request.amendmentId(), amendmentVersion);
            AmendmentResult result = new AmendmentResult(request.amendmentId(), request.merchantId(),
                "STALE", amendmentVersion + 1, merchantStatus, merchantVersion,
                originVersion, originStatus, amendment.get("created_at", OffsetDateTime.class));
            amendmentAudit(tx, actor, result, "PENDING_REVIEW", "STALE",
                "PLATFORM_AMENDMENT_STALE");
            amendmentReviewReceipt(tx, actor, request, result);
            return new AmendmentDecisionOutcome(true, result);
        }
        String next = request.decision().name().equals("APPROVE") ? "APPROVED" : "REJECTED";
        Long appliedVersion = "APPROVED".equals(next) ? merchantVersion + 1 : null;
        int changed = tx.execute("""
            UPDATE merchant_amendment
               SET status=?,row_version=row_version+1,decision=?,decision_reason_code=?,
                   decided_by_tenant_id=?,decided_by_membership_id=?,
                   decided_at=CAST(? AS timestamptz),applied_merchant_version=?,
                   updated_at=CAST(? AS timestamptz)
             WHERE id=? AND row_version=? AND status='PENDING_REVIEW'
            """, next, request.decision().name(), request.reasonCode(), actor.tenantId(),
            actor.membershipId(), now, appliedVersion, now, request.amendmentId(), amendmentVersion);
        if (changed != 1) throw new MerchantException.OptimisticLockConflict();
        if ("APPROVED".equals(next)) {
            applyAmendment(tx, amendment, merchant, now);
            merchantVersion++;
            applyAmendmentMarkets(tx, amendment, now);
            applyAmendmentDocuments(tx, amendment, now);
            MerchantMutationResult merchantResult = new MerchantMutationResult(
                request.merchantId(), merchant.get("merchant_code", String.class),
                merchantStatus, merchantVersion);
            audit(tx, actor, merchantResult, merchantStatus,
                "PROFILE_AMENDMENT_VERIFIED", fullProfileFields());
        }
        AmendmentResult result = new AmendmentResult(request.amendmentId(), request.merchantId(),
            next, amendmentVersion + 1, merchantStatus, merchantVersion,
            originVersion, originStatus, amendment.get("created_at", OffsetDateTime.class));
        amendmentAudit(tx, actor, result, "PENDING_REVIEW", request.decision().name(),
            request.reasonCode());
        amendmentReviewReceipt(tx, actor, request, result);
        return new AmendmentDecisionOutcome(false, result);
    }

    private MerchantMutationResult insertSubmission(
        DSLContext tx, MerchantActor actor, SubmissionCommand command, IdempotencyDigest digest
    ) {
        MerchantLifecyclePolicy.next(null, MerchantCommand.SUBMIT);
        long merchantId = nextId(tx);
        OffsetDateTime now = databaseNow(tx);
        ProtectedRegistration protectedNumber = protect(tx, merchantId, actor.tenantId(),
            command.registrationCountry(), Objects.requireNonNull(command.normalizedRegistrationNumber()));
        String merchantCode = merchantCode(merchantId);
        tx.insertInto(MERCHANT)
            .columns(ID, TENANT_ID, field(name("account_domain"), String.class), CODE,
                LEGAL_NAME, DISPLAY_NAME, COUNTRY, MASKED, FINGERPRINT, SEARCH_KEY,
                FINGERPRINT_ALGORITHM, NORMALIZATION_VERSION, CIPHERTEXT, NONCE, AUTH_TAG,
                AEAD_KEY, AEAD_ALGORITHM_FIELD, STATUS, STATUS_REASON, ROW_VERSION, SUBMITTED_AT)
            .values(merchantId, actor.tenantId(), AccountDomain.MERCHANT.name(), merchantCode,
                command.legalName(), command.displayName(), command.registrationCountry(),
                protectedNumber.masked(), protectedNumber.fingerprint().value(),
                protectedNumber.fingerprint().searchKeyId(), protectedNumber.fingerprint().algorithm(),
                protectedNumber.normalizationVersion(), protectedNumber.ciphertext(),
                protectedNumber.nonce(), protectedNumber.authenticationTag(),
                protectedNumber.aeadKeyId(), protectedNumber.algorithm(),
                MerchantStatus.PENDING_REVIEW.name(), "APPLICATION_SUBMITTED", 0L, now)
            .execute();
        MerchantMutationResult result = new MerchantMutationResult(
            merchantId, merchantCode, MerchantStatus.PENDING_REVIEW, 0);
        audit(tx, actor, result, null, "APPLICATION_SUBMITTED",
            List.of("legalName", "displayName", "registrationCountry", "registrationNumber"));
        receipt(tx, actor, command.command(), command.idempotencyKey(), digest, result, 1);
        return result;
    }

    private MerchantMutationResult updateResubmission(
        DSLContext tx, MerchantActor actor, SubmissionCommand command,
        IdempotencyDigest digest, Record current
    ) {
        MerchantStatus currentStatus = MerchantStatus.valueOf(current.get(STATUS));
        MerchantStatus next = MerchantLifecyclePolicy.next(currentStatus, MerchantCommand.RESUBMIT);
        long version = current.get(ROW_VERSION);
        if (!Objects.equals(command.expectedVersion(), version)) {
            throw new MerchantException.OptimisticLockConflict();
        }
        String currentCountry = current.get(COUNTRY);
        ProtectedRegistration protectedNumber = null;
        if (command.normalizedRegistrationNumber() != null) {
            protectedNumber = protect(tx, current.get(ID), current.get(TENANT_ID),
                command.registrationCountry(), command.normalizedRegistrationNumber());
        } else if (!currentCountry.equals(command.registrationCountry())) {
            throw new MerchantException.InvalidRequest(
                "registrationNumber is required when registrationCountry changes");
        }
        var update = tx.update(MERCHANT)
            .set(LEGAL_NAME, command.legalName())
            .set(DISPLAY_NAME, command.displayName())
            .set(COUNTRY, command.registrationCountry())
            .set(STATUS, next.name())
            .set(STATUS_REASON, "APPLICATION_RESUBMITTED")
            .set(ROW_VERSION, version + 1)
            .set(SUBMITTED_AT, databaseNow(tx));
        if (protectedNumber != null) {
            update.set(MASKED, protectedNumber.masked())
                .set(FINGERPRINT, protectedNumber.fingerprint().value())
                .set(SEARCH_KEY, protectedNumber.fingerprint().searchKeyId())
                .set(FINGERPRINT_ALGORITHM, protectedNumber.fingerprint().algorithm())
                .set(NORMALIZATION_VERSION, protectedNumber.normalizationVersion())
                .set(CIPHERTEXT, protectedNumber.ciphertext())
                .set(NONCE, protectedNumber.nonce())
                .set(AUTH_TAG, protectedNumber.authenticationTag())
                .set(AEAD_KEY, protectedNumber.aeadKeyId())
                .set(AEAD_ALGORITHM_FIELD, protectedNumber.algorithm());
        }
        int changed = update.where(ID.eq(current.get(ID)).and(ROW_VERSION.eq(version))).execute();
        if (changed != 1) throw new MerchantException.OptimisticLockConflict();
        MerchantMutationResult result = new MerchantMutationResult(
            current.get(ID), current.get(CODE), next, version + 1);
        List<String> changedFields = changedFields(current, command, protectedNumber != null);
        audit(tx, actor, result, currentStatus, "APPLICATION_RESUBMITTED", changedFields);
        receipt(tx, actor, command.command(), command.idempotencyKey(), digest, result, 1);
        return result;
    }

    private MerchantMutationResult transition(DSLContext tx, MerchantActor actor, TransitionCommand command) {
        authorize(tx, actor, command.command().permission());
        if (command.command() == MerchantCommand.APPROVE || command.command() == MerchantCommand.REJECT) {
            Record author = tx.fetchOne("""
                SELECT application_source,application_actor_tenant_id,
                       application_author_membership_id
                  FROM merchant WHERE id=? FOR UPDATE
                """, command.merchantId());
            if (author == null) throw new MerchantException.ResourceNotFound();
            if (AccountDomain.PLATFORM.name().equals(author.get(0, String.class))
                && Objects.equals(author.get(1, Long.class), actor.tenantId())
                && Objects.equals(author.get(2, Long.class), actor.membershipId())) {
                throw new MerchantException.PermissionDenied();
            }
        }
        lockIdempotencyKey(tx, actor, command.command(), command.idempotencyKey());
        MerchantMutationResult replay = replay(tx, actor, command.command(), command.idempotencyKey(),
            keyId -> transitionDigest(command, keyId, DIGEST_SCHEME_VERSION));
        if (replay != null) return replay;
        IdempotencyDigest digest = transitionDigest(command, activeIdempotencyKeyId, DIGEST_SCHEME_VERSION);

        Record current = tx.select(detailFields()).from(MERCHANT)
            .where(ID.eq(command.merchantId())).forUpdate().fetchOne();
        if (current == null) throw new MerchantException.ResourceNotFound();
        long version = current.get(ROW_VERSION);
        if (version != command.expectedVersion()) throw new MerchantException.OptimisticLockConflict();
        MerchantStatus previous = MerchantStatus.valueOf(current.get(STATUS));
        MerchantStatus next = MerchantLifecyclePolicy.next(previous, command.command());
        OffsetDateTime now = databaseNow(tx);
        var update = tx.update(MERCHANT).set(STATUS, next.name())
            .set(STATUS_REASON, command.reasonCode()).set(ROW_VERSION, version + 1);
        if (command.command() == MerchantCommand.APPROVE || command.command() == MerchantCommand.REJECT) {
            update.set(REVIEWED_AT, now)
                .set(LAST_DECISION, command.command().name())
                .set(LAST_REASON, command.reasonCode())
                .set(LAST_ACTOR, actor.membershipId())
                .set(LAST_AT, now);
        }
        if (update.where(ID.eq(command.merchantId()).and(ROW_VERSION.eq(version))).execute() != 1) {
            throw new MerchantException.OptimisticLockConflict();
        }
        MerchantMutationResult result = new MerchantMutationResult(
            current.get(ID), current.get(CODE), next, version + 1);
        audit(tx, actor, result, previous, command.reasonCode(), List.of());
        receipt(tx, actor, command.command(), command.idempotencyKey(), digest, result, null);
        return result;
    }

    private MerchantMutationResult updateProfile(
        DSLContext tx, MerchantActor actor, ProfileUpdateCommand command
    ) {
        MerchantCommand operation = MerchantCommand.UPDATE_PROFILE;
        authorize(tx, actor, operation.permission());
        lockIdempotencyKey(tx, actor, operation, command.idempotencyKey());
        boolean legacyReplay = command.merchantTypeCode() == null;
        int schemaVersion = legacyReplay ? COMMAND_SCHEMA_VERSION : PROFILE_COMMAND_SCHEMA_VERSION;
        MerchantMutationResult replay = replay(tx, actor, operation, command.idempotencyKey(),
            schemaVersion,
            keyId -> profileDigest(command, keyId, DIGEST_SCHEME_VERSION, schemaVersion));
        if (replay != null) return replay;
        throw new MerchantException.StateConflict();
    }

    private void authorize(DSLContext tx, MerchantActor actor, String permissionCode) {
        String domain = actor.accountDomain().name();
        Record tenant = tx.fetchOne("SELECT status, account_domain FROM iam_tenant WHERE id=? FOR SHARE",
            actor.tenantId());
        if (tenant == null || !ACTIVE.equals(tenant.get(0, String.class))
            || !domain.equals(tenant.get(1, String.class))) deny();

        Record membership = tx.fetchOne("""
            SELECT user_id,status,account_domain,permission_version,session_version
              FROM iam_membership WHERE tenant_id=? AND id=? FOR SHARE
            """, actor.tenantId(), actor.membershipId());
        if (membership == null || membership.get(0, Long.class) != actor.userId()
            || !ACTIVE.equals(membership.get(1, String.class))
            || !domain.equals(membership.get(2, String.class))
            || membership.get(3, Long.class) != actor.permissionVersion()
            || membership.get(4, Long.class) != actor.sessionVersion()) deny();

        Record user = tx.fetchOne("""
            SELECT status,account_domain,identity_version,idp_issuer,idp_subject
              FROM iam_user WHERE id=? FOR UPDATE
            """, actor.userId());
        Record credential = tx.fetchOne("""
            SELECT status,account_domain,password_hash
              FROM iam_authentication_credential WHERE user_id=? FOR UPDATE
            """, actor.userId());
        validateIdentity(actor, domain, user, credential);

        tx.fetch("""
            SELECT role_id FROM iam_membership_role
             WHERE tenant_id=? AND membership_id=? ORDER BY role_id FOR UPDATE
            """, actor.tenantId(), actor.membershipId());
        tx.fetch("""
            SELECT id FROM iam_role
             WHERE tenant_id=? AND system_role AND NOT assignable
               AND status='ACTIVE' AND deleted_at IS NULL
               AND id IN (SELECT role_id FROM iam_membership_role
                           WHERE tenant_id=? AND membership_id=?)
             ORDER BY id FOR UPDATE
            """, actor.tenantId(), actor.tenantId(), actor.membershipId());
        tx.fetch("""
            SELECT grant_row.id FROM iam_role_grant grant_row
              JOIN iam_permission permission ON permission.id=grant_row.permission_id
             WHERE grant_row.tenant_id=?
               AND grant_row.role_id IN (SELECT role_id FROM iam_membership_role
                                          WHERE tenant_id=? AND membership_id=?)
               AND permission.permission_code IN (?,?)
             ORDER BY grant_row.id FOR UPDATE OF grant_row
            """, actor.tenantId(), actor.tenantId(), actor.membershipId(),
            permissionCode, portalPermission(actor.accountDomain()));
        tx.fetch("""
            SELECT dimension.id FROM iam_grant_dimension dimension
             WHERE dimension.grant_id IN (
               SELECT grant_row.id FROM iam_role_grant grant_row
                 JOIN iam_permission permission ON permission.id=grant_row.permission_id
                WHERE grant_row.tenant_id=?
                  AND grant_row.role_id IN (SELECT role_id FROM iam_membership_role
                                             WHERE tenant_id=? AND membership_id=?)
                  AND permission.permission_code IN (?,?))
             ORDER BY dimension.id FOR UPDATE
            """, actor.tenantId(), actor.tenantId(), actor.membershipId(),
            permissionCode, portalPermission(actor.accountDomain()));
        tx.fetch("""
            SELECT target.id FROM iam_grant_target target
             WHERE target.dimension_id IN (
               SELECT dimension.id FROM iam_grant_dimension dimension
                WHERE dimension.grant_id IN (
                  SELECT grant_row.id FROM iam_role_grant grant_row
                    JOIN iam_permission permission ON permission.id=grant_row.permission_id
                   WHERE grant_row.tenant_id=?
                     AND grant_row.role_id IN (SELECT role_id FROM iam_membership_role
                                                WHERE tenant_id=? AND membership_id=?)
                     AND permission.permission_code IN (?,?)))
             ORDER BY target.id FOR UPDATE
            """, actor.tenantId(), actor.tenantId(), actor.membershipId(),
            permissionCode, portalPermission(actor.accountDomain()));
        tx.fetch("SELECT id FROM iam_permission WHERE permission_code IN (?,?) ORDER BY id FOR UPDATE",
            permissionCode, portalPermission(actor.accountDomain()));

        List<Record> proof = tx.fetch("""
            SELECT action_grant.id, action_permission.requires_step_up
              FROM iam_role role_row
              JOIN iam_membership_role assignment
                ON assignment.tenant_id=role_row.tenant_id AND assignment.role_id=role_row.id
              JOIN iam_role_grant action_grant
                ON action_grant.tenant_id=role_row.tenant_id AND action_grant.role_id=role_row.id
              JOIN iam_permission action_permission ON action_permission.id=action_grant.permission_id
             WHERE role_row.tenant_id=? AND assignment.membership_id=?
               AND role_row.system_role AND NOT role_row.assignable
               AND role_row.status='ACTIVE' AND role_row.deleted_at IS NULL
               AND action_permission.permission_code=? AND action_permission.status='ACTIVE'
               AND action_permission.cross_tenant_mode='SAME_TENANT_ONLY'
               AND action_permission.required_dimensions=ARRAY['TENANT']::varchar(32)[]
               AND action_grant.status='ACTIVE'
               AND action_grant.valid_from IS NOT NULL AND action_grant.valid_until IS NOT NULL
               AND action_grant.valid_from<=statement_timestamp()
               AND action_grant.valid_until>statement_timestamp()
               AND (SELECT count(*) FROM iam_grant_dimension d
                     WHERE d.grant_id=action_grant.id AND d.dimension_code='TENANT'
                       AND d.scope_mode='TENANT_ALL'
                       AND NOT EXISTS (SELECT 1 FROM iam_grant_target t WHERE t.dimension_id=d.id))=1
               AND (SELECT count(*) FROM iam_grant_dimension d WHERE d.grant_id=action_grant.id)=1
               AND EXISTS (
                 SELECT 1 FROM iam_role_grant portal_grant
                   JOIN iam_permission portal_permission ON portal_permission.id=portal_grant.permission_id
                  WHERE portal_grant.tenant_id=role_row.tenant_id
                    AND portal_grant.role_id=role_row.id
                    AND portal_grant.grant_key='system-backoffice-access'
                    AND portal_grant.status='ACTIVE'
                    AND (portal_grant.valid_from IS NULL OR portal_grant.valid_from<=statement_timestamp())
                    AND (portal_grant.valid_until IS NULL OR portal_grant.valid_until>statement_timestamp())
                    AND portal_permission.permission_code=? AND portal_permission.status='ACTIVE'
                    AND (SELECT count(*) FROM iam_grant_dimension d
                          WHERE d.grant_id=portal_grant.id AND d.dimension_code='TENANT'
                            AND d.scope_mode='TENANT_ALL'
                            AND NOT EXISTS (SELECT 1 FROM iam_grant_target t WHERE t.dimension_id=d.id))=1
                    AND (SELECT count(*) FROM iam_grant_dimension d WHERE d.grant_id=portal_grant.id)=1)
            """, actor.tenantId(), actor.membershipId(), permissionCode,
            portalPermission(actor.accountDomain()));
        if (proof.size() != 1 || (proof.getFirst().get(1, Boolean.class)
            && !actor.stepUpVerified())) deny();
    }

    private static void validateIdentity(
        MerchantActor actor, String domain, Record user, Record credential
    ) {
        if (user == null || credential == null || !ACTIVE.equals(user.get(0, String.class))
            || !domain.equals(user.get(1, String.class))
            || user.get(2, Long.class) != actor.identityVersion()
            || !ACTIVE.equals(credential.get(0, String.class))
            || !domain.equals(credential.get(1, String.class))) deny();
        String passwordHash = credential.get(2, String.class);
        if (actor.issuer() == null) {
            if (passwordHash == null) deny();
            return;
        }
        if (!actor.issuer().equals(user.get(3, String.class))
            || !actor.subject().equals(user.get(4, String.class))
            || (!actor.federated() && passwordHash == null)) deny();
    }

    private ProtectedRegistration protect(
        DSLContext tx, long merchantId, long tenantId, String country, String normalized
    ) {
        tx.fetchOne("SELECT pg_advisory_xact_lock(hashtext('mch-registration-write'))");
        tx.fetchOne("SELECT pg_advisory_xact_lock(hashtext('mch-registration-key-rotation'))");
        List<Record> active = tx.fetch("""
            SELECT key_purpose,key_id,algorithm FROM merchant_registration_key_metadata
             WHERE active ORDER BY key_purpose
            """);
        Record search = active.stream().filter(row -> "REGISTRATION_SEARCH_HMAC".equals(row.get(0))
            && SEARCH_ALGORITHM.equals(row.get(2))).findFirst().orElse(null);
        Record aead = active.stream().filter(row -> "REGISTRATION_AEAD".equals(row.get(0))
            && AEAD_ALGORITHM.equals(row.get(2))).findFirst().orElse(null);
        if (search == null || aead == null
            || active.stream().filter(row -> "REGISTRATION_SEARCH_HMAC".equals(row.get(0))).count() != 1
            || active.stream().filter(row -> "REGISTRATION_AEAD".equals(row.get(0))).count() != 1) {
            throw new MerchantException.ProtectedFieldUnavailable();
        }
        ProtectedRegistration result = cryptography.protect(merchantId, tenantId, country, normalized,
            search.get(1, String.class), aead.get(1, String.class));
        tx.execute("""
            INSERT INTO merchant_protected_nonce(purpose,key_id,algorithm,nonce)
            VALUES ('REGISTRATION_AEAD',?,?,?)
            """, result.aeadKeyId(), result.algorithm(), result.nonce());
        return result;
    }

    private MerchantMutationResult replay(
        DSLContext tx, MerchantActor actor, MerchantCommand command,
        java.util.UUID key, Function<String, IdempotencyDigest> digestFactory
    ) {
        return replay(tx, actor, command, key, COMMAND_SCHEMA_VERSION, digestFactory);
    }

    private MerchantMutationResult replay(
        DSLContext tx, MerchantActor actor, MerchantCommand command, java.util.UUID key,
        int expectedSchemaVersion, Function<String, IdempotencyDigest> digestFactory
    ) {
        Record row = tx.fetchOne("""
            SELECT request_digest,idempotency_hmac_key_id,command_schema_version,
                   canonical_digest_scheme_version,required_permission,merchant_id,
                   result_merchant_code,result_status,result_row_version
              FROM merchant_command_dedup
             WHERE actor_account_domain=? AND actor_membership_id=?
               AND command_type=? AND idempotency_key=?
            """, actor.accountDomain().name(), actor.membershipId(), command.name(), key);
        if (row == null) return null;
        int schemaVersion = row.get(2, Integer.class);
        int schemeVersion = row.get(3, Integer.class);
        if (schemeVersion != DIGEST_SCHEME_VERSION) {
            throw new MerchantException.ProtectedFieldUnavailable();
        }
        if (schemaVersion != expectedSchemaVersion) {
            if (schemaVersion == COMMAND_SCHEMA_VERSION
                || schemaVersion == PROFILE_COMMAND_SCHEMA_VERSION) {
                throw new MerchantException.IdempotencyConflict();
            }
            throw new MerchantException.ProtectedFieldUnavailable();
        }
        IdempotencyDigest recorded = new IdempotencyDigest(schemeVersion, SEARCH_ALGORITHM,
            row.get(1, String.class), row.get(0, byte[].class));
        IdempotencyDigest recalculated = digestFactory.apply(recorded.keyId());
        if (!recorded.matches(recalculated)
            || !command.permission().equals(row.get(4, String.class))) {
            throw new MerchantException.IdempotencyConflict();
        }
        return new MerchantMutationResult(row.get(5, Long.class), row.get(6, String.class),
            MerchantStatus.valueOf(row.get(7, String.class)), row.get(8, Long.class));
    }

    private static void lockIdempotencyKey(
        DSLContext tx, MerchantActor actor, MerchantCommand command, java.util.UUID key
    ) {
        tx.fetchOne(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            actor.accountDomain().name() + ':' + actor.membershipId() + ':' + command.name() + ':' + key);
    }

    private void receipt(
        DSLContext tx, MerchantActor actor, MerchantCommand command, java.util.UUID key,
        IdempotencyDigest digest, MerchantMutationResult result, Integer normalizationVersion
    ) {
        receipt(tx, actor, command, key, digest, result, normalizationVersion,
            COMMAND_SCHEMA_VERSION);
    }

    private void receipt(
        DSLContext tx, MerchantActor actor, MerchantCommand command, java.util.UUID key,
        IdempotencyDigest digest, MerchantMutationResult result, Integer normalizationVersion,
        int commandSchemaVersion
    ) {
        tx.execute("""
            INSERT INTO merchant_command_dedup(
              id,actor_account_domain,actor_tenant_id,actor_membership_id,command_type,
              idempotency_key,request_digest,idempotency_hmac_key_id,command_schema_version,
              canonical_digest_scheme_version,registration_normalization_version,
              required_permission,merchant_id,result_merchant_code,result_status,result_row_version)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, nextId(tx), actor.accountDomain().name(), actor.tenantId(), actor.membershipId(),
            command.name(), key, digest.value(), digest.keyId(), commandSchemaVersion,
            digest.schemeVersion(), normalizationVersion, command.permission(), result.merchantId(),
            result.merchantCode(), result.status().name(), result.rowVersion());
    }

    private void audit(
        DSLContext tx, MerchantActor actor, MerchantMutationResult result,
        MerchantStatus previous, String reason, List<String> changedFields
    ) {
        String traceId = Objects.requireNonNull(traceIdSupplier.get(), "traceId");
        if (traceId.isBlank() || traceId.length() > 64) {
            throw new IllegalStateException("Invalid trace identifier");
        }
        tx.execute("""
            INSERT INTO merchant_audit_event(
              id,merchant_id,target_tenant_id,actor_account_domain,actor_tenant_id,
              actor_membership_id,action_code,previous_status,next_status,reason_code,
              changed_fields,trace_id,merchant_version)
            VALUES (?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?)
            """, nextId(tx), result.merchantId(), targetTenant(tx, result.merchantId()),
            actor.accountDomain().name(), actor.tenantId(), actor.membershipId(),
            auditAction(previous, result.status(), reason), previous == null ? null : previous.name(),
            result.status().name(), reason, changedFieldsJson(changedFields),
            traceId, result.rowVersion());
    }

    private static String auditAction(MerchantStatus previous, MerchantStatus next, String reason) {
        if ("PLATFORM_APPLICATION_SUBMITTED".equals(reason)) return "CREATE";
        if (previous == null) return "SUBMIT";
        if ("APPLICATION_RESUBMITTED".equals(reason)) return "RESUBMIT";
        if ("PLATFORM_PROFILE_UPDATED".equals(reason)) return "UPDATE_PROFILE";
        if ("PROFILE_AMENDMENT_VERIFIED".equals(reason)) return "UPDATE_AMENDMENT";
        if (previous == MerchantStatus.PENDING_REVIEW && next == MerchantStatus.ACTIVE) return "APPROVE";
        if (previous == MerchantStatus.PENDING_REVIEW && next == MerchantStatus.REVIEW_REJECTED) return "REJECT";
        if (next == MerchantStatus.DISABLED) return "DISABLE";
        if (previous == MerchantStatus.DISABLED && next == MerchantStatus.ACTIVE) return "ENABLE";
        if (next == MerchantStatus.TERMINATED) return "TERMINATE";
        throw new MerchantException.StateConflict();
    }

    private static long targetTenant(DSLContext tx, long merchantId) {
        Long tenantId = tx.select(TENANT_ID).from(MERCHANT).where(ID.eq(merchantId)).fetchOne(TENANT_ID);
        if (tenantId == null) throw new MerchantException.ResourceNotFound();
        return tenantId;
    }

    private IdempotencyDigest submissionDigest(SubmissionCommand command, String keyId, int version) {
        return cryptography.idempotencyDigest(keyId, version, List.of(
            Integer.toString(COMMAND_SCHEMA_VERSION), command.command().name(),
            command.expectedVersion() == null ? "<null>" : command.expectedVersion().toString(),
            command.legalName(), command.displayName(), command.registrationCountry(),
            command.normalizedRegistrationNumber() == null ? "<null>" : command.normalizedRegistrationNumber()));
    }

    private IdempotencyDigest transitionDigest(TransitionCommand command, String keyId, int version) {
        return cryptography.idempotencyDigest(keyId, version, List.of(
            Integer.toString(COMMAND_SCHEMA_VERSION), command.command().name(),
            Long.toString(command.merchantId()), Long.toString(command.expectedVersion()),
            command.reasonCode()));
    }

    private IdempotencyDigest profileDigest(
        ProfileUpdateCommand command, String keyId, int version, int schemaVersion
    ) {
        List<String> values = new ArrayList<>(List.of(
            Integer.toString(schemaVersion), MerchantCommand.UPDATE_PROFILE.name(),
            Long.toString(command.merchantId()), Long.toString(command.expectedVersion()),
            command.legalName(), command.displayName()));
        if (schemaVersion == PROFILE_COMMAND_SCHEMA_VERSION) {
            values.add(command.merchantTypeCode());
            values.add(command.legalPersonName());
            values.add(command.authenticationType());
        }
        values.add(command.remarks());
        values.addAll(command.marketCodes());
        return cryptography.idempotencyDigest(keyId, version, values);
    }

    private Condition platformCondition(MerchantQuery query) {
        Condition condition = noCondition();
        if (query.merchantCode() != null) condition = condition.and(CODE.eq(query.merchantCode()));
        if (query.name() != null) {
            String pattern = "%" + query.name().toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            condition = condition.and(LEGAL_NAME.lower().like(pattern, '\\')
                .or(DISPLAY_NAME.lower().like(pattern, '\\')));
        }
        if (query.status() != null) condition = condition.and(STATUS.eq(query.status().name()));
        if (query.registrationCountry() != null) condition = condition.and(COUNTRY.eq(query.registrationCountry()));
        if (query.marketCode() != null) {
            condition = condition.andExists(selectOne().from(OPERATING_MARKET)
                .where(MARKET_MERCHANT_ID.eq(ID)
                    .and(MARKET_CODE.eq(query.marketCode()))
                    .and(MARKET_STATUS.eq(ACTIVE))));
        }
        if (query.merchantTypeCode() != null) {
            condition = condition.and(MERCHANT_TYPE.eq(query.merchantTypeCode()));
        }
        if (query.authenticationType() != null) {
            condition = condition.and(AUTHENTICATION_TYPE.eq(query.authenticationType()));
        }
        if (query.createdFrom() != null) condition = condition.and(CREATED_AT.ge(query.createdFrom()));
        if (query.createdTo() != null) condition = condition.and(CREATED_AT.lt(query.createdTo()));
        return condition;
    }

    private MerchantDetail detail(Record row, List<String> marketCodes) {
        String lastDecision = row.get(LAST_DECISION);
        MerchantModels.LastDecision decision = lastDecision == null ? null : new MerchantModels.LastDecision(
            lastDecision, row.get(LAST_REASON), row.get(LAST_AT), row.get(LAST_ACTOR));
        return new MerchantDetail(row.get(ID), row.get(TENANT_ID), row.get(CODE), row.get(LEGAL_NAME),
            row.get(DISPLAY_NAME), row.get(MERCHANT_TYPE), row.get(LEGAL_PERSON),
            row.get(AUTHENTICATION_TYPE), row.get(COUNTRY), row.get(MASKED), row.get(REMARKS),
            row.get(STATUS_REASON), marketCodes, MerchantStatus.valueOf(row.get(STATUS)),
            row.get(ROW_VERSION), row.get(SUBMITTED_AT),
            row.get(REVIEWED_AT), decision, row.get(CREATED_AT), row.get(UPDATED_AT));
    }

    private MerchantDetail effectiveDetail(DSLContext tx, Record row, List<String> marketCodes) {
        MerchantDetail base = detail(row, marketCodes);
        Map<DocumentKind, DocumentMetadata> documents = currentDocuments(tx, row.get(ID));
        com.niv.payment.merchant.core.MerchantOnboardingModels.LegalIdValidity validity =
            row.get(LEGAL_ID_VALID_FROM) == null ? null
                : new com.niv.payment.merchant.core.MerchantOnboardingModels.LegalIdValidity(
                    row.get(LEGAL_ID_VALID_FROM), row.get(LEGAL_ID_VALID_TO));
        return new MerchantDetail(base.merchantId(), base.tenantId(), base.merchantCode(),
            base.legalName(), base.displayName(), base.merchantTypeCode(), base.legalPersonName(),
            base.authenticationType(), base.registrationCountry(), base.registrationNumberMasked(),
            base.remarks(), base.statusReasonCode(), base.marketCodes(), base.status(),
            base.rowVersion(), base.submittedAt(), base.reviewedAt(), base.lastDecision(),
            base.createdAt(), base.updatedAt(), row.get(BRAND_NAME), row.get(INDUSTRY_CODE),
            row.get(REGISTERED_ADDRESS), row.get(OPERATING_ADDRESS), row.get(CONTACT_EMAIL),
            row.get(CONTACT_PHONE), row.get(LEGAL_ID_TYPE), validity, row.get(LEGAL_ID_MASKED),
            documents.get(DocumentKind.BRAND_LOGO), documents.get(DocumentKind.BUSINESS_LICENSE),
            documents.get(DocumentKind.LEGAL_ID_FRONT), documents.get(DocumentKind.LEGAL_ID_BACK),
            documents.get(DocumentKind.LEGAL_ID_HOLDING));
    }

    private static Map<DocumentKind, DocumentMetadata> currentDocuments(DSLContext tx, long merchantId) {
        var result = new java.util.EnumMap<DocumentKind, DocumentMetadata>(DocumentKind.class);
        for (Record row : tx.fetch("""
            SELECT document.id,document.target_tenant_id,document.kind,document.media_type,
                   document.width,document.height,document.size_bytes,document.expires_at
              FROM merchant_document_binding binding
              JOIN merchant_document document ON document.id=binding.document_id
             WHERE binding.merchant_id=? AND document.attachment_scope='MERCHANT'
               AND document.deleted_at IS NULL
            """, merchantId)) {
            DocumentKind kind = DocumentKind.valueOf(row.get(2, String.class));
            result.put(kind, new DocumentMetadata(row.get(0, Long.class), row.get(1, Long.class),
                kind, row.get(3, String.class), row.get(4, Integer.class), row.get(5, Integer.class),
                row.get(6, Integer.class), row.get(7, OffsetDateTime.class), true));
        }
        return Map.copyOf(result);
    }

    private static Field<?>[] detailFields() {
        return new Field<?>[]{ID, TENANT_ID, CODE, LEGAL_NAME, DISPLAY_NAME,
            MERCHANT_TYPE, LEGAL_PERSON, AUTHENTICATION_TYPE, COUNTRY, MASKED,
            REMARKS, STATUS_REASON, BRAND_NAME, INDUSTRY_CODE, REGISTERED_ADDRESS,
            OPERATING_ADDRESS, CONTACT_EMAIL, CONTACT_PHONE, LEGAL_ID_TYPE, LEGAL_ID_MASKED,
            LEGAL_ID_VALID_FROM, LEGAL_ID_VALID_TO,
            STATUS, ROW_VERSION, SUBMITTED_AT, REVIEWED_AT, LAST_DECISION, LAST_REASON,
            LAST_ACTOR, LAST_AT, CREATED_AT, UPDATED_AT};
    }

    private static List<String> profileChangedFields(
        Record current, List<String> currentMarkets, ProfileUpdateCommand command
    ) {
        List<String> fields = new ArrayList<>();
        if (!current.get(LEGAL_NAME).equals(command.legalName())) fields.add("legalName");
        if (!current.get(DISPLAY_NAME).equals(command.displayName())) fields.add("displayName");
        if (!Objects.equals(current.get(MERCHANT_TYPE), command.merchantTypeCode())) {
            fields.add("merchantTypeCode");
        }
        if (!Objects.equals(current.get(LEGAL_PERSON), command.legalPersonName())) {
            fields.add("legalPersonName");
        }
        if (!Objects.equals(current.get(AUTHENTICATION_TYPE), command.authenticationType())) {
            fields.add("authenticationType");
        }
        if (!current.get(REMARKS).equals(command.remarks())) fields.add("remarks");
        if (!currentMarkets.equals(command.marketCodes())) fields.add("marketCodes");
        return List.copyOf(fields);
    }

    private static List<String> marketCodes(DSLContext tx, long merchantId) {
        return tx.select(MARKET_CODE).from(OPERATING_MARKET)
            .where(MARKET_MERCHANT_ID.eq(merchantId).and(MARKET_STATUS.eq(ACTIVE)))
            .orderBy(MARKET_CODE).fetch(MARKET_CODE);
    }

    private static Map<Long, List<String>> marketCodes(DSLContext tx, List<Long> merchantIds) {
        if (merchantIds.isEmpty()) return Map.of();
        return tx.select(MARKET_MERCHANT_ID, MARKET_CODE).from(OPERATING_MARKET)
            .where(MARKET_MERCHANT_ID.in(merchantIds).and(MARKET_STATUS.eq(ACTIVE)))
            .orderBy(MARKET_MERCHANT_ID, MARKET_CODE)
            .fetchGroups(MARKET_MERCHANT_ID, MARKET_CODE);
    }

    private static List<String> changedFields(
        Record current, SubmissionCommand command, boolean numberChanged
    ) {
        List<String> fields = new ArrayList<>();
        if (!current.get(LEGAL_NAME).equals(command.legalName())) fields.add("legalName");
        if (!current.get(DISPLAY_NAME).equals(command.displayName())) fields.add("displayName");
        if (!current.get(COUNTRY).equals(command.registrationCountry())) fields.add("registrationCountry");
        if (numberChanged) fields.add("registrationNumber");
        return List.copyOf(fields);
    }

    private static String changedFieldsJson(List<String> values) {
        return values.stream().map(value -> "\"" + value + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static OffsetDateTime databaseNow(DSLContext tx) {
        return tx.fetchOne("SELECT statement_timestamp()").get(0, OffsetDateTime.class);
    }

    private static long nextId(DSLContext tx) {
        Long result = tx.select(IAM_ID_SEQ.nextval()).fetchOne(IAM_ID_SEQ.nextval());
        if (result == null) throw new IllegalStateException("IAM sequence returned no identifier");
        return result;
    }

    private static String merchantCode(long merchantId) {
        String value = Long.toString(merchantId, 36).toUpperCase(java.util.Locale.ROOT);
        return "MCH_" + "0".repeat(Math.max(0, 12 - value.length())) + value;
    }

    private static String portalPermission(AccountDomain domain) {
        return domain == AccountDomain.PLATFORM
            ? "backoffice:platform-access" : "backoffice:merchant-access";
    }

    private static void requireDomain(MerchantActor actor, AccountDomain expected) {
        Objects.requireNonNull(actor, "actor");
        if (actor.accountDomain() != expected) deny();
    }

    private static void deny() {
        throw new MerchantException.PermissionDenied();
    }

    private static RuntimeException mapConflict(DataAccessException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return new MerchantException.DataConflict();
            }
            cause = cause.getCause();
        }
        return exception;
    }

    private void requireOnboardingCryptography() {
        if (onboardingCryptography == null) throw new MerchantException.ProtectedFieldUnavailable();
    }

    private static void requireEligibleTarget(DSLContext tx, long tenantId, boolean unbound) {
        Record target = tx.fetchOne("""
            SELECT id FROM iam_tenant
             WHERE id=? AND account_domain='MERCHANT' AND status='ACTIVE'
             FOR UPDATE
            """, tenantId);
        if (target == null) throw new MerchantException.ResourceNotFound();
        if (unbound && tx.fetchExists(selectOne().from(MERCHANT).where(TENANT_ID.eq(tenantId)))) {
            throw new MerchantException.DataConflict();
        }
    }

    private static String activeProtectionKey(DSLContext tx, String purpose) {
        List<String> keys = tx.fetch("""
            SELECT key_id FROM merchant_registration_key_metadata
             WHERE key_purpose=? AND active AND algorithm='AES-256-GCM'
            """, purpose).getValues(0, String.class);
        if (keys.size() != 1) throw new MerchantException.ProtectedFieldUnavailable();
        return keys.getFirst();
    }

    private static void lockProtectionFences(
        DSLContext tx, boolean registration, boolean legalId, boolean document
    ) {
        if (registration) {
            tx.fetchOne("SELECT pg_advisory_xact_lock(hashtext('mch-registration-write'))");
            tx.fetchOne("SELECT pg_advisory_xact_lock(hashtext('mch-registration-key-rotation'))");
        }
        if (legalId) {
            tx.fetchOne("SELECT pg_advisory_xact_lock(hashtext('mch-legal-id-key-rotation'))");
        }
        if (document) {
            tx.fetchOne("SELECT pg_advisory_xact_lock(hashtext('mch-document-key-rotation'))");
        }
    }

    private static void allocateNonce(DSLContext tx, String purpose, ProtectedPayload payload) {
        tx.execute("""
            INSERT INTO merchant_protected_nonce(purpose,key_id,algorithm,nonce)
            VALUES (?,?,?,?)
            """, purpose, payload.keyId(), payload.algorithm(), payload.nonce());
    }

    private static void requireRegistrationAvailable(
        DSLContext tx, long merchantId, String country, byte[] fingerprint
    ) {
        if (tx.fetchExists(selectOne().from(MERCHANT).where(COUNTRY.eq(country)
            .and(FINGERPRINT.eq(fingerprint)).and(ID.ne(merchantId))))) {
            throw new MerchantException.DataConflict();
        }
    }

    private static Map<DocumentKind, Long> documentIds(Profile profile) {
        Map<DocumentKind, Long> values = Map.of(
            DocumentKind.BRAND_LOGO, profile.brandLogoDocumentId(),
            DocumentKind.BUSINESS_LICENSE, profile.businessLicenseDocumentId(),
            DocumentKind.LEGAL_ID_FRONT, profile.legalIdFrontDocumentId(),
            DocumentKind.LEGAL_ID_BACK, profile.legalIdBackDocumentId(),
            DocumentKind.LEGAL_ID_HOLDING, profile.legalIdHoldingDocumentId());
        if (new HashSet<>(values.values()).size() != values.size()) {
            throw new MerchantException.InvalidRequest("Merchant documents must be distinct");
        }
        return values;
    }

    private static void lockAttachableDocuments(
        DSLContext tx, MerchantActor actor, long targetTenantId, Map<DocumentKind, Long> documents
    ) {
        List<Record> rows = tx.fetch("""
            SELECT id,kind FROM merchant_document
             WHERE id=ANY(?::bigint[]) AND target_tenant_id=? AND actor_tenant_id=?
               AND actor_membership_id=? AND attachment_scope='TEMPORARY'
               AND deleted_at IS NULL AND expires_at>statement_timestamp()
             ORDER BY id FOR UPDATE
            """, documents.values().toArray(Long[]::new), targetTenantId,
            actor.tenantId(), actor.membershipId());
        if (rows.size() != documents.size()) throw new MerchantException.DocumentAttachmentConflict();
        for (Record row : rows) {
            long id = row.get(0, Long.class);
            DocumentKind kind;
            try { kind = DocumentKind.valueOf(row.get(1, String.class)); }
            catch (IllegalArgumentException exception) { throw new MerchantException.DocumentAttachmentConflict(); }
            if (!Objects.equals(documents.get(kind), id)) {
                throw new MerchantException.DocumentAttachmentConflict();
            }
        }
    }

    private static Map<DocumentKind, AmendmentDocumentReference> lockAmendmentDocuments(
        DSLContext tx, MerchantActor actor, long merchantId, long targetTenantId,
        Map<DocumentKind, Long> documents
    ) {
        var current = new java.util.EnumMap<DocumentKind, Long>(DocumentKind.class);
        for (Record row : tx.fetch("""
            SELECT binding.kind,binding.document_id
              FROM merchant_document_binding binding
              JOIN merchant_document document ON document.id=binding.document_id
             WHERE binding.merchant_id=? AND document.attachment_scope='MERCHANT'
               AND document.merchant_id=? AND document.target_tenant_id=?
               AND document.deleted_at IS NULL
             ORDER BY binding.kind
             FOR UPDATE OF binding,document
            """, merchantId, merchantId, targetTenantId)) {
            current.put(DocumentKind.valueOf(row.get(0, String.class)), row.get(1, Long.class));
        }

        var temporary = new java.util.EnumMap<DocumentKind, Long>(DocumentKind.class);
        for (Record row : tx.fetch("""
            SELECT id,kind FROM merchant_document
             WHERE id=ANY(?::bigint[]) AND target_tenant_id=? AND actor_tenant_id=?
               AND actor_membership_id=? AND attachment_scope='TEMPORARY'
               AND deleted_at IS NULL AND expires_at>statement_timestamp()
             ORDER BY id FOR UPDATE
            """, documents.values().toArray(Long[]::new), targetTenantId,
            actor.tenantId(), actor.membershipId())) {
            DocumentKind kind;
            try { kind = DocumentKind.valueOf(row.get(1, String.class)); }
            catch (IllegalArgumentException exception) {
                throw new MerchantException.DocumentAttachmentConflict();
            }
            temporary.put(kind, row.get(0, Long.class));
        }

        var result = new java.util.EnumMap<DocumentKind, AmendmentDocumentReference>(
            DocumentKind.class);
        int replacementCount = 0;
        for (var entry : documents.entrySet()) {
            if (Objects.equals(current.get(entry.getKey()), entry.getValue())) {
                result.put(entry.getKey(), new AmendmentDocumentReference(
                    entry.getValue(), AmendmentDocumentMode.RETAIN));
            } else if (Objects.equals(temporary.get(entry.getKey()), entry.getValue())) {
                result.put(entry.getKey(), new AmendmentDocumentReference(
                    entry.getValue(), AmendmentDocumentMode.REPLACE));
                replacementCount += 1;
            } else {
                throw new MerchantException.DocumentAttachmentConflict();
            }
        }
        if (temporary.size() != replacementCount || result.size() != documents.size()) {
            throw new MerchantException.DocumentAttachmentConflict();
        }
        return Map.copyOf(result);
    }

    private static void attachDocumentsToMerchant(
        DSLContext tx, long merchantId, Map<DocumentKind, Long> documents, OffsetDateTime now
    ) {
        for (var entry : documents.entrySet()) {
            int changed = tx.execute("""
                UPDATE merchant_document SET attachment_scope='MERCHANT',merchant_id=?,
                       attached_at=CAST(? AS timestamptz)
                 WHERE id=? AND attachment_scope='TEMPORARY'
                """, merchantId, now, entry.getValue());
            if (changed != 1) throw new MerchantException.DocumentAttachmentConflict();
            tx.execute("""
                INSERT INTO merchant_document_binding(merchant_id,kind,document_id,bound_at)
                VALUES (?,?,?,CAST(? AS timestamptz))
                """, merchantId, entry.getKey().name(), entry.getValue(), now);
        }
    }

    private static void replaceMarkets(
        DSLContext tx, long merchantId, long tenantId, List<String> markets, OffsetDateTime now
    ) {
        for (String market : markets) {
            tx.execute("""
                INSERT INTO merchant_operating_market(
                  merchant_id,target_tenant_id,market_code,status,activated_at)
                VALUES (?,?,?,'ACTIVE',CAST(? AS timestamptz))
                """, merchantId, tenantId, market, now);
        }
    }

    private DocumentContent decryptDocument(long documentId, Record row, int offset) {
        long targetTenantId = row.get(offset, Long.class);
        long actorMembershipId = row.get(offset + 1, Long.class);
        DocumentKind kind;
        try { kind = DocumentKind.valueOf(row.get(offset + 2, String.class)); }
        catch (IllegalArgumentException exception) { throw new MerchantException.ProtectedFieldUnavailable(); }
        String mediaType = row.get(offset + 3, String.class);
        int width = row.get(offset + 4, Integer.class);
        int height = row.get(offset + 5, Integer.class);
        int size = row.get(offset + 6, Integer.class);
        ProtectedPayload payload = new ProtectedPayload(
            row.get(offset + 12, Integer.class), row.get(offset + 13, Integer.class),
            row.get(offset + 11, String.class), row.get(offset + 10, String.class),
            row.get(offset + 8, byte[].class), row.get(offset + 7, byte[].class),
            row.get(offset + 9, byte[].class), null);
        return new DocumentContent(mediaType, onboardingCryptography.decryptDocument(
            documentId, targetTenantId, actorMembershipId, kind, mediaType, width, height,
            size, payload));
    }

    private IdempotencyDigest onboardingDigest(
        String command, long targetId, Long expectedVersion, Profile profile, String keyId
    ) {
        List<String> values = new ArrayList<>(List.of(
            Integer.toString(ONBOARDING_COMMAND_SCHEMA_VERSION), command,
            Long.toString(targetId), expectedVersion == null ? "<null>" : expectedVersion.toString(),
            profile.displayName(), profile.brandName(), profile.authenticationType(),
            profile.merchantTypeCode(), profile.industryCode(),
            profile.brandLogoDocumentId().toString(), profile.legalName(),
            profile.registrationCountry(), profile.registeredAddress(), profile.operatingAddress(),
            profile.businessLicenseDocumentId().toString(), profile.legalPersonName(),
            profile.contactEmail(), profile.contactPhone(), profile.legalIdTypeCode(),
            profile.legalIdNo().mode().name(),
            profile.legalIdNo().value() == null ? "<null>" : profile.legalIdNo().value(),
            profile.legalIdValidity().validFrom().toString(),
            profile.legalIdValidity().validTo().toString(),
            profile.legalIdFrontDocumentId().toString(),
            profile.legalIdBackDocumentId().toString(),
            profile.legalIdHoldingDocumentId().toString(), profile.remarks(),
            profile.registrationNumber().mode().name(),
            profile.registrationNumber().value() == null ? "<null>" : profile.registrationNumber().value()));
        values.addAll(profile.marketCodes());
        return cryptography.idempotencyDigest(keyId, DIGEST_SCHEME_VERSION, values);
    }

    private static List<String> fullProfileFields() {
        return List.of("displayName","brandName","authenticationType","merchantTypeCode",
            "industryCode","brandLogoDocument","legalName","registrationCountry",
            "registrationNumber","marketCodes","registeredAddress","operatingAddress",
            "businessLicenseDocument","legalPersonName","contactEmail","contactPhone",
            "legalIdTypeCode","legalIdNo","legalIdValidity","legalIdFrontDocument",
            "legalIdBackDocument","legalIdHoldingDocument","remarks");
    }

    private static String escapedLike(String value) {
        if (value == null) return null;
        return "%" + value.toLowerCase(java.util.Locale.ROOT)
            .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static ProtectedRegistration registrationFrom(Record row, int offset) {
        return new ProtectedRegistration(1, row.get(offset + 4, Integer.class),
            row.get(offset + 9, String.class), row.get(offset + 8, String.class),
            row.get(offset + 6, byte[].class), row.get(offset + 5, byte[].class),
            row.get(offset + 7, byte[].class), row.get(offset, String.class),
            new com.niv.payment.merchant.core.crypto.RegistrationFingerprint(
                1, row.get(offset + 4, Integer.class), row.get(offset + 3, String.class),
                row.get(offset + 2, String.class), row.get(offset + 1, byte[].class)));
    }

    private static ProtectedPayload legalIdFrom(Record row, int offset) {
        return new ProtectedPayload(row.get(offset + 6, Integer.class),
            row.get(offset + 7, Integer.class), row.get(offset + 5, String.class),
            row.get(offset + 4, String.class), row.get(offset + 2, byte[].class),
            row.get(offset + 1, byte[].class), row.get(offset + 3, byte[].class),
            row.get(offset, String.class));
    }

    private static void attachDocumentsToAmendment(
        DSLContext tx, long merchantId, long amendmentId,
        Map<DocumentKind, AmendmentDocumentReference> documents,
        OffsetDateTime now
    ) {
        for (var entry : documents.entrySet()) {
            AmendmentDocumentReference reference = entry.getValue();
            if (reference.mode() == AmendmentDocumentMode.REPLACE) {
                int changed = tx.execute("""
                    UPDATE merchant_document
                       SET attachment_scope='AMENDMENT',merchant_id=?,amendment_id=?,
                           attached_at=CAST(? AS timestamptz)
                     WHERE id=? AND attachment_scope='TEMPORARY'
                    """, merchantId, amendmentId, now, reference.documentId());
                if (changed != 1) throw new MerchantException.DocumentAttachmentConflict();
            }
            tx.execute("""
                INSERT INTO merchant_amendment_document(
                  amendment_id,kind,document_id,document_mode)
                VALUES (?,?,?,?)
                """, amendmentId, entry.getKey().name(), reference.documentId(),
                reference.mode().name());
        }
    }

    private static Map<DocumentKind, DocumentMetadata> amendmentDocuments(
        DSLContext tx, long amendmentId
    ) {
        var result = new java.util.EnumMap<DocumentKind, DocumentMetadata>(DocumentKind.class);
        for (Record row : tx.fetch("""
            SELECT document.id,document.target_tenant_id,document.kind,document.media_type,
                   document.width,document.height,document.size_bytes,document.expires_at
              FROM merchant_amendment reference_owner
              JOIN merchant_amendment_document reference
                ON reference.amendment_id=reference_owner.id
              JOIN merchant_document document
                ON document.id=reference.document_id AND document.kind=reference.kind
               AND document.target_tenant_id=reference_owner.target_tenant_id
              LEFT JOIN merchant_document_binding binding
                ON binding.merchant_id=reference_owner.merchant_id
               AND binding.kind=reference.kind AND binding.document_id=document.id
             WHERE reference_owner.id=? AND document.deleted_at IS NULL
               AND (
                 (reference.document_mode='REPLACE'
                   AND document.attachment_scope='AMENDMENT'
                   AND document.amendment_id=reference_owner.id
                   AND document.merchant_id=reference_owner.merchant_id)
                 OR
                 (reference.document_mode='RETAIN'
                   AND document.attachment_scope='MERCHANT'
                   AND document.merchant_id=reference_owner.merchant_id
                   AND binding.document_id IS NOT NULL)
               )
            """, amendmentId)) {
            DocumentKind kind = DocumentKind.valueOf(row.get(2, String.class));
            result.put(kind, new DocumentMetadata(row.get(0, Long.class), row.get(1, Long.class),
                kind, row.get(3, String.class), row.get(4, Integer.class),
                row.get(5, Integer.class), row.get(6, Integer.class),
                row.get(7, OffsetDateTime.class), true));
        }
        return Map.copyOf(result);
    }

    private void applyAmendment(DSLContext tx, Record amendment, Record merchant, OffsetDateTime now) {
        tx.fetchOne("SELECT pg_advisory_xact_lock(hashtext('mch-registration-write'))");
        requireRegistrationAvailable(tx, amendment.get("merchant_id", Long.class),
            amendment.get("registration_country", String.class),
            amendment.get("registration_fingerprint", byte[].class));
        int changed = tx.execute("""
            UPDATE merchant SET
              display_name=?,brand_name=?,authentication_type=?,merchant_type_code=?,
              industry_code=?,legal_name=?,registration_country=?,registered_address=?,
              operating_address=?,legal_person_name=?,contact_email=?,contact_phone=?,
              legal_id_type_code=?,legal_id_valid_from=?,legal_id_valid_to=?,remarks=?,
              registration_number_masked=?,registration_fingerprint=?,
              registration_search_key_id=?,registration_fingerprint_algorithm=?,
              registration_normalization_version=?,registration_ciphertext=?,registration_nonce=?,
              registration_auth_tag=?,registration_aead_key_id=?,registration_aead_algorithm=?,
              legal_id_no_masked=?,legal_id_ciphertext=?,legal_id_nonce=?,legal_id_auth_tag=?,
              legal_id_aead_key_id=?,legal_id_aead_algorithm=?,legal_id_aad_scheme_version=?,
              legal_id_protection_version=?,row_version=row_version+1,
              updated_at=CAST(? AS timestamptz)
             WHERE id=? AND row_version=? AND status=?
            """, amendment.get("display_name"), amendment.get("brand_name"),
            amendment.get("authentication_type"), amendment.get("merchant_type_code"),
            amendment.get("industry_code"), amendment.get("legal_name"),
            amendment.get("registration_country"), amendment.get("registered_address"),
            amendment.get("operating_address"), amendment.get("legal_person_name"),
            amendment.get("contact_email"), amendment.get("contact_phone"),
            amendment.get("legal_id_type_code"), amendment.get("legal_id_valid_from"),
            amendment.get("legal_id_valid_to"), amendment.get("remarks"),
            amendment.get("registration_number_masked"), amendment.get("registration_fingerprint"),
            amendment.get("registration_search_key_id"),
            amendment.get("registration_fingerprint_algorithm"),
            amendment.get("registration_normalization_version"),
            amendment.get("registration_ciphertext"), amendment.get("registration_nonce"),
            amendment.get("registration_auth_tag"), amendment.get("registration_aead_key_id"),
            amendment.get("registration_aead_algorithm"), amendment.get("legal_id_no_masked"),
            amendment.get("legal_id_ciphertext"), amendment.get("legal_id_nonce"),
            amendment.get("legal_id_auth_tag"), amendment.get("legal_id_aead_key_id"),
            amendment.get("legal_id_aead_algorithm"), amendment.get("legal_id_aad_scheme_version"),
            amendment.get("legal_id_protection_version"), now, merchant.get("id"),
            merchant.get("row_version"), merchant.get("status"));
        if (changed != 1) throw new MerchantException.OptimisticLockConflict();
    }

    private static void applyAmendmentMarkets(DSLContext tx, Record amendment, OffsetDateTime now) {
        long merchantId = amendment.get("merchant_id", Long.class);
        long tenantId = amendment.get("target_tenant_id", Long.class);
        List<String> desired = tx.fetch(
            "SELECT market_code FROM merchant_amendment_market WHERE amendment_id=?",
            amendment.get("id")).getValues(0, String.class);
        tx.execute("""
            UPDATE merchant_operating_market SET status='INACTIVE',
                   deactivated_at=CAST(? AS timestamptz),updated_at=CAST(? AS timestamptz)
             WHERE merchant_id=? AND status='ACTIVE' AND NOT (market_code=ANY(?::text[]))
            """, now, now, merchantId, desired.toArray(String[]::new));
        for (String market : desired) {
            tx.execute("""
                INSERT INTO merchant_operating_market(
                  merchant_id,target_tenant_id,market_code,status,activated_at)
                VALUES (?,?,?,'ACTIVE',CAST(? AS timestamptz))
                ON CONFLICT (merchant_id,market_code) DO UPDATE
                  SET status='ACTIVE',activated_at=EXCLUDED.activated_at,
                      deactivated_at=NULL,updated_at=EXCLUDED.activated_at
                """, merchantId, tenantId, market, now);
        }
    }

    private static void applyAmendmentDocuments(DSLContext tx, Record amendment, OffsetDateTime now) {
        long merchantId = amendment.get("merchant_id", Long.class);
        List<Record> documents = tx.fetch("""
            SELECT document.id,reference.kind,reference.document_mode
              FROM merchant_amendment_document reference
              JOIN merchant_document document ON document.id=reference.document_id
             WHERE reference.amendment_id=? AND document.deleted_at IS NULL
             ORDER BY reference.kind FOR UPDATE OF document
            """, amendment.get("id"));
        if (documents.size() != DocumentKind.values().length) {
            throw new MerchantException.ProtectedFieldUnavailable();
        }
        for (Record row : documents) {
            long documentId = row.get(0, Long.class);
            String kind = row.get(1, String.class);
            AmendmentDocumentMode mode;
            try { mode = AmendmentDocumentMode.valueOf(row.get(2, String.class)); }
            catch (IllegalArgumentException exception) {
                throw new MerchantException.ProtectedFieldUnavailable();
            }
            Record previousRow = tx.fetchOne("""
                SELECT document_id FROM merchant_document_binding
                 WHERE merchant_id=? AND kind=? FOR UPDATE
                """, merchantId, kind);
            Long previous = previousRow == null ? null : previousRow.get(0, Long.class);
            if (mode == AmendmentDocumentMode.RETAIN) {
                if (!Objects.equals(previous, documentId)
                    || tx.fetchOne("""
                        SELECT 1 FROM merchant_document
                         WHERE id=? AND merchant_id=? AND kind=?
                           AND attachment_scope='MERCHANT' AND deleted_at IS NULL
                        """, documentId, merchantId, kind) == null) {
                    throw new MerchantException.ProtectedFieldUnavailable();
                }
                continue;
            }
            if (previous != null) {
                tx.execute("UPDATE merchant_document SET superseded_at=CAST(? AS timestamptz) "
                    + "WHERE id=?", now, previous);
            }
            tx.execute("""
                INSERT INTO merchant_document_binding(merchant_id,kind,document_id,bound_at)
                VALUES (?,?,?,CAST(? AS timestamptz))
                ON CONFLICT (merchant_id,kind) DO UPDATE
                  SET document_id=EXCLUDED.document_id,bound_at=EXCLUDED.bound_at
                """, merchantId, kind, documentId, now);
            tx.execute("""
                UPDATE merchant_document
                   SET attachment_scope='MERCHANT',amendment_id=NULL,
                       attached_at=CAST(? AS timestamptz)
                 WHERE id=? AND attachment_scope='AMENDMENT'
                """, now, documentId);
        }
    }

    private static void lockAmendmentIdempotencyKey(
        DSLContext tx, MerchantActor actor, String command, java.util.UUID key
    ) {
        tx.fetchOne("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
            "MCH-AMEND:" + actor.membershipId() + ':' + command + ':' + key);
    }

    private AmendmentResult replayAmendment(
        DSLContext tx, MerchantActor actor, String command, java.util.UUID key,
        Function<String, IdempotencyDigest> digestFactory
    ) {
        Record row = tx.fetchOne("""
            SELECT request_digest,idempotency_hmac_key_id,command_schema_version,
                   canonical_digest_scheme_version,required_permission,amendment_id,merchant_id,
                   result_status,result_row_version,result_merchant_status,
                   result_merchant_row_version
              FROM merchant_amendment_command_dedup
             WHERE actor_membership_id=? AND command_type=? AND idempotency_key=?
            """, actor.membershipId(), command, key);
        if (row == null) return null;
        IdempotencyDigest recorded = new IdempotencyDigest(row.get(3, Integer.class),
            SEARCH_ALGORITHM, row.get(1, String.class), row.get(0, byte[].class));
        if (row.get(2, Integer.class) != ONBOARDING_COMMAND_SCHEMA_VERSION
            || !recorded.matches(digestFactory.apply(recorded.keyId()))) {
            throw new MerchantException.IdempotencyConflict();
        }
        Record amendment = tx.fetchOne("""
            SELECT origin_merchant_version,origin_status,created_at FROM merchant_amendment WHERE id=?
            """, row.get(5, Long.class));
        return new AmendmentResult(row.get(5, Long.class), row.get(6, Long.class),
            row.get(7, String.class), row.get(8, Long.class),
            MerchantStatus.valueOf(row.get(9, String.class)), row.get(10, Long.class),
            amendment.get(0, Long.class), MerchantStatus.valueOf(amendment.get(1, String.class)),
            amendment.get(2, OffsetDateTime.class));
    }

    private AmendmentResult replayAmendmentReview(
        DSLContext tx, MerchantActor actor, AmendmentReviewRequest request
    ) {
        return replayAmendment(tx, actor, "REVIEW", request.idempotencyKey(), keyId ->
            amendmentReviewDigest(request, keyId));
    }

    private void amendmentReceipt(
        DSLContext tx, MerchantActor actor, String command, java.util.UUID key,
        IdempotencyDigest digest, AmendmentResult result, String permission
    ) {
        tx.execute("""
            INSERT INTO merchant_amendment_command_dedup(
              id,actor_tenant_id,actor_membership_id,command_type,idempotency_key,request_digest,
              idempotency_hmac_key_id,command_schema_version,canonical_digest_scheme_version,
              required_permission,amendment_id,merchant_id,result_status,result_row_version,
              result_merchant_status,result_merchant_row_version)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, nextId(tx), actor.tenantId(), actor.membershipId(), command, key,
            digest.value(), digest.keyId(), ONBOARDING_COMMAND_SCHEMA_VERSION,
            digest.schemeVersion(), permission, result.amendmentId(), result.merchantId(),
            result.status(), result.rowVersion(), result.merchantStatus().name(),
            result.merchantRowVersion());
    }

    private void amendmentReviewReceipt(
        DSLContext tx, MerchantActor actor, AmendmentReviewRequest request, AmendmentResult result
    ) {
        amendmentReceipt(tx, actor, "REVIEW", request.idempotencyKey(),
            amendmentReviewDigest(request, activeIdempotencyKeyId), result, "merchant:review");
    }

    private IdempotencyDigest amendmentReviewDigest(
        AmendmentReviewRequest request, String keyId
    ) {
        return cryptography.idempotencyDigest(keyId, DIGEST_SCHEME_VERSION, List.of(
            Integer.toString(ONBOARDING_COMMAND_SCHEMA_VERSION), "REVIEW",
            Long.toString(request.merchantId()), Long.toString(request.amendmentId()),
            Long.toString(request.expectedVersion()), request.decision().name(),
            request.reasonCode()));
    }

    private void amendmentAudit(
        DSLContext tx, MerchantActor actor, AmendmentResult result, String previous,
        String action, String reason
    ) {
        String traceId = Objects.requireNonNull(traceIdSupplier.get(), "traceId");
        tx.execute("""
            INSERT INTO merchant_amendment_audit_event(
              id,amendment_id,merchant_id,actor_tenant_id,actor_membership_id,action_code,
              previous_status,next_status,reason_code,amendment_version,trace_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """, nextId(tx), result.amendmentId(), result.merchantId(), actor.tenantId(),
            actor.membershipId(), action, previous, result.status(), reason,
            result.rowVersion(), traceId);
    }

    private record AmendmentDecisionOutcome(boolean stale, AmendmentResult result) { }

    private static String requiredKeyId(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new MerchantException.ProtectedFieldUnavailable();
        }
        return value;
    }
}
