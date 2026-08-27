package com.niv.payment.merchant.persistence;

import com.niv.payment.merchant.core.MerchantException;
import com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentKind;
import com.niv.payment.merchant.core.MerchantProtectedDataKeyRotation;
import com.niv.payment.merchant.core.MerchantRegistrationKeyRotation;
import com.niv.payment.merchant.core.crypto.MerchantCryptography;
import com.niv.payment.merchant.core.crypto.MerchantOnboardingCryptography;
import com.niv.payment.merchant.core.crypto.ProtectedPayload;
import com.niv.payment.merchant.core.crypto.ProtectedRegistration;
import com.niv.payment.merchant.core.crypto.RegistrationFingerprint;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;

import java.util.Base64;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/** Serialized, all-row registration protection rotation and metadata cutover. */
public final class JooqMerchantRegistrationKeyRotation
    implements MerchantRegistrationKeyRotation, MerchantProtectedDataKeyRotation {
    private static final String SEARCH_PURPOSE = "REGISTRATION_SEARCH_HMAC";
    private static final String AEAD_PURPOSE = "REGISTRATION_AEAD";
    private static final String SEARCH_ALGORITHM = "HMAC-SHA-256";
    private static final String AEAD_ALGORITHM = "AES-256-GCM";
    private static final String LEGAL_ID_PURPOSE = "LEGAL_ID_AEAD";
    private static final String DOCUMENT_PURPOSE = "DOCUMENT_AEAD";

    private final DSLContext dsl;
    private final MerchantCryptography cryptography;
    private final MerchantOnboardingCryptography onboardingCryptography;

    public JooqMerchantRegistrationKeyRotation(DSLContext dsl, MerchantCryptography cryptography) {
        this(dsl, cryptography, null);
    }

    public JooqMerchantRegistrationKeyRotation(
        DSLContext dsl,
        MerchantCryptography cryptography,
        MerchantOnboardingCryptography onboardingCryptography
    ) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.cryptography = Objects.requireNonNull(cryptography, "cryptography");
        this.onboardingCryptography = onboardingCryptography;
    }

    @Override
    public RotationResult rotate(String targetSearchKeyId, String targetAeadKeyId) {
        requireKeyId(targetSearchKeyId);
        requireKeyId(targetAeadKeyId);
        try {
            return dsl.transactionResult(configuration -> rotate(configuration.dsl(),
                targetSearchKeyId, targetAeadKeyId));
        } catch (MerchantException.ProtectedFieldUnavailable exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new MerchantException.ProtectedFieldUnavailable(exception);
        }
    }

    @Override
    public FullRotationResult rotateAll(
        String targetSearchKeyId,
        String targetRegistrationAeadKeyId,
        String targetLegalIdAeadKeyId,
        String targetDocumentAeadKeyId
    ) {
        requireKeyId(targetSearchKeyId);
        requireKeyId(targetRegistrationAeadKeyId);
        requireKeyId(targetLegalIdAeadKeyId);
        requireKeyId(targetDocumentAeadKeyId);
        if (onboardingCryptography == null) throw unavailable();
        try {
            return dsl.transactionResult(configuration -> rotateAll(configuration.dsl(),
                targetSearchKeyId, targetRegistrationAeadKeyId,
                targetLegalIdAeadKeyId, targetDocumentAeadKeyId));
        } catch (MerchantException.ProtectedFieldUnavailable exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new MerchantException.ProtectedFieldUnavailable(exception);
        }
    }

    private RotationResult rotate(DSLContext tx, String searchKeyId, String aeadKeyId) {
        assumeRotationRole(tx);
        lockProtectionFences(tx, false);
        lockMetadata(tx);
        requireExternalRegistrationKeys(searchKeyId, aeadKeyId);
        ensureMetadata(tx, SEARCH_PURPOSE, searchKeyId, SEARCH_ALGORITHM);
        ensureMetadata(tx, AEAD_PURPOSE, aeadKeyId, AEAD_ALGORITHM);

        RotationCounts counts = rotateRegistrationEvidence(tx, searchKeyId, aeadKeyId);
        requireRegistrationCoverage(tx, searchKeyId, aeadKeyId,
            counts.merchants(), counts.amendments());
        activate(tx, SEARCH_PURPOSE, searchKeyId);
        activate(tx, AEAD_PURPOSE, aeadKeyId);
        requireActiveMetadata(tx, List.of(
            new ActiveKey(SEARCH_PURPOSE, searchKeyId, SEARCH_ALGORITHM),
            new ActiveKey(AEAD_PURPOSE, aeadKeyId, AEAD_ALGORITHM)));
        return new RotationResult(searchKeyId, aeadKeyId, counts.merchants());
    }

    private FullRotationResult rotateAll(
        DSLContext tx,
        String searchKeyId,
        String registrationAeadKeyId,
        String legalIdAeadKeyId,
        String documentAeadKeyId
    ) {
        assumeRotationRole(tx);
        lockProtectionFences(tx, true);
        lockMetadata(tx);
        requireExternalRegistrationKeys(searchKeyId, registrationAeadKeyId);
        requireExternalOnboardingKeys(legalIdAeadKeyId, documentAeadKeyId);
        ensureMetadata(tx, SEARCH_PURPOSE, searchKeyId, SEARCH_ALGORITHM);
        ensureMetadata(tx, AEAD_PURPOSE, registrationAeadKeyId, AEAD_ALGORITHM);
        ensureMetadata(tx, LEGAL_ID_PURPOSE, legalIdAeadKeyId, AEAD_ALGORITHM);
        ensureMetadata(tx, DOCUMENT_PURPOSE, documentAeadKeyId, AEAD_ALGORITHM);

        RotationCounts registration = rotateRegistrationEvidence(
            tx, searchKeyId, registrationAeadKeyId);
        RotationCounts legalId = rotateLegalIdEvidence(tx, legalIdAeadKeyId);
        long documents = rotateDocumentEvidence(tx, documentAeadKeyId);
        requireRegistrationCoverage(tx, searchKeyId, registrationAeadKeyId,
            registration.merchants(), registration.amendments());
        requireLegalIdCoverage(tx, legalIdAeadKeyId,
            legalId.merchants(), legalId.amendments());
        requireDocumentCoverage(tx, documentAeadKeyId, documents);

        activate(tx, SEARCH_PURPOSE, searchKeyId);
        activate(tx, AEAD_PURPOSE, registrationAeadKeyId);
        activate(tx, LEGAL_ID_PURPOSE, legalIdAeadKeyId);
        activate(tx, DOCUMENT_PURPOSE, documentAeadKeyId);
        requireActiveMetadata(tx, List.of(
            new ActiveKey(SEARCH_PURPOSE, searchKeyId, SEARCH_ALGORITHM),
            new ActiveKey(AEAD_PURPOSE, registrationAeadKeyId, AEAD_ALGORITHM),
            new ActiveKey(LEGAL_ID_PURPOSE, legalIdAeadKeyId, AEAD_ALGORITHM),
            new ActiveKey(DOCUMENT_PURPOSE, documentAeadKeyId, AEAD_ALGORITHM)));
        return new FullRotationResult(searchKeyId, registrationAeadKeyId,
            legalIdAeadKeyId, documentAeadKeyId,
            registration.merchants(), registration.amendments(),
            legalId.merchants(), legalId.amendments(), documents);
    }

    private static void assumeRotationRole(DSLContext tx) {
        tx.execute("SET LOCAL ROLE payment_merchant_registration_rotation");
        Record principal = tx.fetchOne("""
            SELECT current_user, session_user,
                   session_role.rolsuper, session_role.rolcreaterole,
                   pg_has_role(session_user, capability.oid, 'SET'),
                   membership.admin_option, membership.inherit_option,
                   membership.set_option,
                   (SELECT count(*) FROM pg_auth_members all_memberships
                     WHERE all_memberships.roleid=capability.oid
                        OR all_memberships.member=capability.oid)
              FROM pg_roles session_role
              JOIN pg_auth_members membership ON membership.member=session_role.oid
              JOIN pg_roles capability ON capability.oid=membership.roleid
             WHERE session_role.rolname=session_user
               AND capability.rolname='payment_merchant_registration_rotation'
            """);
        if (principal == null
            || !"payment_merchant_registration_rotation".equals(principal.get(0, String.class))
            || "payment_merchant_registration_rotation".equals(principal.get(1, String.class))
            || !Boolean.FALSE.equals(principal.get(2, Boolean.class))
            || !Boolean.FALSE.equals(principal.get(3, Boolean.class))
            || !Boolean.TRUE.equals(principal.get(4, Boolean.class))
            || !Boolean.FALSE.equals(principal.get(5, Boolean.class))
            || !Boolean.FALSE.equals(principal.get(6, Boolean.class))
            || !Boolean.TRUE.equals(principal.get(7, Boolean.class))
            || !Long.valueOf(1).equals(principal.get(8, Long.class))) {
            throw unavailable();
        }
    }

    private static void lockProtectionFences(DSLContext tx, boolean allPurposes) {
        tx.fetchOne("SELECT pg_advisory_xact_lock(hashtext('mch-registration-write'))");
        tx.fetchOne("SELECT pg_advisory_xact_lock(hashtext('mch-registration-key-rotation'))");
        if (allPurposes) {
            tx.fetchOne("SELECT pg_advisory_xact_lock(hashtext('mch-legal-id-key-rotation'))");
            tx.fetchOne("SELECT pg_advisory_xact_lock(hashtext('mch-document-key-rotation'))");
        }
    }

    private static void lockMetadata(DSLContext tx) {
        tx.fetch("SELECT key_purpose,key_id,algorithm,active "
            + "FROM merchant_registration_key_metadata ORDER BY key_purpose,key_id FOR UPDATE");
    }

    private RotationCounts rotateRegistrationEvidence(
        DSLContext tx, String searchKeyId, String aeadKeyId
    ) {
        List<Record> merchants = tx.fetch("""
            SELECT id,tenant_id,registration_country,registration_number_masked,
                   registration_fingerprint,registration_search_key_id,
                   registration_fingerprint_algorithm,registration_normalization_version,
                   registration_ciphertext,registration_nonce,registration_auth_tag,
                   registration_aead_key_id,registration_aead_algorithm
              FROM merchant ORDER BY id FOR UPDATE
            """);
        List<Record> amendments = tx.fetch("""
            SELECT id,merchant_id,target_tenant_id,registration_country,
                   registration_number_masked,registration_fingerprint,
                   registration_search_key_id,registration_fingerprint_algorithm,
                   registration_normalization_version,registration_ciphertext,
                   registration_nonce,registration_auth_tag,registration_aead_key_id,
                   registration_aead_algorithm
              FROM merchant_amendment ORDER BY id FOR UPDATE
            """);
        var fingerprints = new HashMap<String, Long>();
        for (Record row : merchants) {
            long merchantId = row.get("id", Long.class);
            long tenantId = row.get("tenant_id", Long.class);
            String country = row.get("registration_country", String.class);
            ProtectedRegistration rotated = rotateRegistration(
                merchantId, tenantId, country, protectedRegistration(row),
                searchKeyId, aeadKeyId);
            requireUniqueRegistration(fingerprints, merchantId, country, rotated);
            allocateNonce(tx, AEAD_PURPOSE, rotated.aeadKeyId(),
                rotated.algorithm(), rotated.nonce());
            int changed = tx.execute("""
                UPDATE merchant
                   SET registration_fingerprint=?, registration_search_key_id=?,
                       registration_fingerprint_algorithm=?, registration_ciphertext=?,
                       registration_nonce=?, registration_auth_tag=?, registration_aead_key_id=?,
                       registration_aead_algorithm=?
                 WHERE id=?
                """, rotated.fingerprint().value(), rotated.fingerprint().searchKeyId(),
                rotated.fingerprint().algorithm(), rotated.ciphertext(), rotated.nonce(),
                rotated.authenticationTag(), rotated.aeadKeyId(), rotated.algorithm(), merchantId);
            if (changed != 1) throw unavailable();
        }
        for (Record row : amendments) {
            long amendmentId = row.get("id", Long.class);
            long merchantId = row.get("merchant_id", Long.class);
            long tenantId = row.get("target_tenant_id", Long.class);
            String country = row.get("registration_country", String.class);
            ProtectedRegistration rotated = rotateRegistration(
                merchantId, tenantId, country, protectedRegistration(row),
                searchKeyId, aeadKeyId);
            requireUniqueRegistration(fingerprints, merchantId, country, rotated);
            allocateNonce(tx, AEAD_PURPOSE, rotated.aeadKeyId(),
                rotated.algorithm(), rotated.nonce());
            int changed = tx.execute("""
                UPDATE merchant_amendment
                   SET registration_fingerprint=?, registration_search_key_id=?,
                       registration_fingerprint_algorithm=?, registration_ciphertext=?,
                       registration_nonce=?, registration_auth_tag=?, registration_aead_key_id=?,
                       registration_aead_algorithm=?
                 WHERE id=?
                """, rotated.fingerprint().value(), rotated.fingerprint().searchKeyId(),
                rotated.fingerprint().algorithm(), rotated.ciphertext(), rotated.nonce(),
                rotated.authenticationTag(), rotated.aeadKeyId(), rotated.algorithm(), amendmentId);
            if (changed != 1) throw unavailable();
        }
        return new RotationCounts(merchants.size(), amendments.size());
    }

    private ProtectedRegistration rotateRegistration(
        long merchantId, long tenantId, String country, ProtectedRegistration current,
        String searchKeyId, String aeadKeyId
    ) {
        String normalized = cryptography.decryptNormalized(merchantId, tenantId, country, current);
        ProtectedRegistration rotated = cryptography.protect(
            merchantId, tenantId, country, normalized, searchKeyId, aeadKeyId);
        if (!normalized.equals(cryptography.decryptNormalized(
            merchantId, tenantId, country, rotated))) throw unavailable();
        return rotated;
    }

    private static void requireUniqueRegistration(
        HashMap<String, Long> fingerprints,
        long merchantId,
        String country,
        ProtectedRegistration rotated
    ) {
        String uniqueness = country + ':' + Base64.getEncoder().encodeToString(
            rotated.fingerprint().value());
        Long owner = fingerprints.putIfAbsent(uniqueness, merchantId);
        if (owner != null && owner != merchantId) throw unavailable();
    }

    private void requireExternalRegistrationKeys(String searchKeyId, String aeadKeyId) {
        RegistrationFingerprint ignored = cryptography.fingerprint(searchKeyId, 1, "SG", "KEYPROBE");
        ProtectedRegistration probe = cryptography.protect(
            Long.MAX_VALUE, Long.MAX_VALUE, "SG", "KEYPROBE", searchKeyId, aeadKeyId);
        if (ignored.value().length != 32 || !"KEYPROBE".equals(cryptography.decryptNormalized(
            Long.MAX_VALUE, Long.MAX_VALUE, "SG", probe))) throw unavailable();
    }

    private void requireExternalOnboardingKeys(String legalIdKeyId, String documentKeyId) {
        ProtectedPayload legalId = onboardingCryptography.protectLegalId(
            Long.MAX_VALUE, Long.MAX_VALUE, "NATIONAL_ID", "KEYPROBE", legalIdKeyId);
        if (!"KEYPROBE".equals(onboardingCryptography.decryptLegalId(
            Long.MAX_VALUE, Long.MAX_VALUE, "NATIONAL_ID", legalId))) throw unavailable();
        byte[] content = new byte[]{1};
        ProtectedPayload document = onboardingCryptography.protectDocument(
            Long.MAX_VALUE - 1, Long.MAX_VALUE - 2, Long.MAX_VALUE - 3,
            DocumentKind.BRAND_LOGO, "image/png", 1, 1, content.length,
            content, documentKeyId);
        byte[] decrypted = onboardingCryptography.decryptDocument(
            Long.MAX_VALUE - 1, Long.MAX_VALUE - 2, Long.MAX_VALUE - 3,
            DocumentKind.BRAND_LOGO, "image/png", 1, 1, content.length, document);
        try {
            if (!Arrays.equals(content, decrypted)) throw unavailable();
        } finally {
            Arrays.fill(content, (byte) 0);
            Arrays.fill(decrypted, (byte) 0);
        }
    }

    private RotationCounts rotateLegalIdEvidence(DSLContext tx, String keyId) {
        List<Record> merchants = tx.fetch("""
            SELECT id,tenant_id,legal_id_type_code,legal_id_no_masked,legal_id_ciphertext,
                   legal_id_nonce,legal_id_auth_tag,legal_id_aead_key_id,
                   legal_id_aead_algorithm,legal_id_aad_scheme_version,
                   legal_id_protection_version
              FROM merchant WHERE legal_id_ciphertext IS NOT NULL ORDER BY id FOR UPDATE
            """);
        List<Record> amendments = tx.fetch("""
            SELECT id,merchant_id,target_tenant_id,legal_id_type_code,legal_id_no_masked,
                   legal_id_ciphertext,legal_id_nonce,legal_id_auth_tag,legal_id_aead_key_id,
                   legal_id_aead_algorithm,legal_id_aad_scheme_version,
                   legal_id_protection_version
              FROM merchant_amendment ORDER BY id FOR UPDATE
            """);
        for (Record row : merchants) {
            long merchantId = row.get("id", Long.class);
            ProtectedPayload rotated = rotateLegalId(merchantId, row.get("tenant_id", Long.class),
                row.get("legal_id_type_code", String.class), legalIdPayload(row), keyId);
            allocateNonce(tx, LEGAL_ID_PURPOSE, rotated.keyId(),
                rotated.algorithm(), rotated.nonce());
            int changed = tx.execute("""
                UPDATE merchant
                   SET legal_id_ciphertext=?,legal_id_nonce=?,legal_id_auth_tag=?,
                       legal_id_aead_key_id=?,legal_id_aead_algorithm=?,
                       legal_id_aad_scheme_version=?,legal_id_protection_version=?
                 WHERE id=?
                """, rotated.ciphertext(), rotated.nonce(), rotated.authenticationTag(),
                rotated.keyId(), rotated.algorithm(), rotated.aadSchemeVersion(),
                rotated.protectionVersion(), merchantId);
            if (changed != 1) throw unavailable();
        }
        for (Record row : amendments) {
            long amendmentId = row.get("id", Long.class);
            long merchantId = row.get("merchant_id", Long.class);
            ProtectedPayload rotated = rotateLegalId(merchantId,
                row.get("target_tenant_id", Long.class),
                row.get("legal_id_type_code", String.class), legalIdPayload(row), keyId);
            allocateNonce(tx, LEGAL_ID_PURPOSE, rotated.keyId(),
                rotated.algorithm(), rotated.nonce());
            int changed = tx.execute("""
                UPDATE merchant_amendment
                   SET legal_id_ciphertext=?,legal_id_nonce=?,legal_id_auth_tag=?,
                       legal_id_aead_key_id=?,legal_id_aead_algorithm=?,
                       legal_id_aad_scheme_version=?,legal_id_protection_version=?
                 WHERE id=?
                """, rotated.ciphertext(), rotated.nonce(), rotated.authenticationTag(),
                rotated.keyId(), rotated.algorithm(), rotated.aadSchemeVersion(),
                rotated.protectionVersion(), amendmentId);
            if (changed != 1) throw unavailable();
        }
        return new RotationCounts(merchants.size(), amendments.size());
    }

    private ProtectedPayload rotateLegalId(
        long merchantId,
        long tenantId,
        String legalIdType,
        ProtectedPayload current,
        String keyId
    ) {
        String normalized = onboardingCryptography.decryptLegalId(
            merchantId, tenantId, legalIdType, current);
        ProtectedPayload rotated = onboardingCryptography.protectLegalId(
            merchantId, tenantId, legalIdType, normalized, keyId);
        if (!normalized.equals(onboardingCryptography.decryptLegalId(
            merchantId, tenantId, legalIdType, rotated))
            || !Objects.equals(current.masked(), rotated.masked())) throw unavailable();
        return rotated;
    }

    private long rotateDocumentEvidence(DSLContext tx, String keyId) {
        List<Record> documents = tx.fetch("""
            SELECT id,target_tenant_id,actor_membership_id,kind,media_type,width,height,size_bytes,
                   ciphertext,nonce,auth_tag,aead_key_id,aead_algorithm,aad_scheme_version,
                   protection_version
              FROM merchant_document ORDER BY id FOR UPDATE
            """);
        for (Record row : documents) {
            long documentId = row.get("id", Long.class);
            long tenantId = row.get("target_tenant_id", Long.class);
            long membershipId = row.get("actor_membership_id", Long.class);
            DocumentKind kind;
            try {
                kind = DocumentKind.valueOf(row.get("kind", String.class));
            } catch (RuntimeException exception) {
                throw unavailable();
            }
            String mediaType = row.get("media_type", String.class);
            int width = row.get("width", Integer.class);
            int height = row.get("height", Integer.class);
            int size = row.get("size_bytes", Integer.class);
            ProtectedPayload current = documentPayload(row);
            byte[] plaintext = onboardingCryptography.decryptDocument(documentId, tenantId,
                membershipId, kind, mediaType, width, height, size, current);
            byte[] verified = null;
            try {
                ProtectedPayload rotated = onboardingCryptography.protectDocument(documentId,
                    tenantId, membershipId, kind, mediaType, width, height, size, plaintext, keyId);
                verified = onboardingCryptography.decryptDocument(documentId, tenantId,
                    membershipId, kind, mediaType, width, height, size, rotated);
                if (!Arrays.equals(plaintext, verified)) throw unavailable();
                allocateNonce(tx, DOCUMENT_PURPOSE, rotated.keyId(),
                    rotated.algorithm(), rotated.nonce());
                int changed = tx.execute("""
                    UPDATE merchant_document
                       SET ciphertext=?,nonce=?,auth_tag=?,aead_key_id=?,aead_algorithm=?,
                           aad_scheme_version=?,protection_version=?
                     WHERE id=?
                    """, rotated.ciphertext(), rotated.nonce(), rotated.authenticationTag(),
                    rotated.keyId(), rotated.algorithm(), rotated.aadSchemeVersion(),
                    rotated.protectionVersion(), documentId);
                if (changed != 1) throw unavailable();
            } finally {
                Arrays.fill(plaintext, (byte) 0);
                if (verified != null) Arrays.fill(verified, (byte) 0);
            }
        }
        return documents.size();
    }

    private static void requireRegistrationCoverage(
        DSLContext tx, String searchKeyId, String aeadKeyId,
        long merchantCount, long amendmentCount
    ) {
        long coveredMerchants = tx.fetchOne("""
            SELECT count(*) FROM merchant
             WHERE registration_search_key_id=? AND registration_fingerprint_algorithm=?
               AND registration_aead_key_id=? AND registration_aead_algorithm=?
            """, searchKeyId, SEARCH_ALGORITHM, aeadKeyId, AEAD_ALGORITHM).get(0, Long.class);
        long coveredAmendments = tx.fetchOne("""
            SELECT count(*) FROM merchant_amendment
             WHERE registration_search_key_id=? AND registration_fingerprint_algorithm=?
               AND registration_aead_key_id=? AND registration_aead_algorithm=?
            """, searchKeyId, SEARCH_ALGORITHM, aeadKeyId, AEAD_ALGORITHM).get(0, Long.class);
        if (coveredMerchants != merchantCount || coveredAmendments != amendmentCount) {
            throw unavailable();
        }
    }

    private static void requireLegalIdCoverage(
        DSLContext tx, String keyId, long merchantCount, long amendmentCount
    ) {
        long coveredMerchants = tx.fetchOne("""
            SELECT count(*) FROM merchant
             WHERE legal_id_ciphertext IS NOT NULL AND legal_id_aead_key_id=?
               AND legal_id_aead_algorithm=? AND legal_id_aad_scheme_version=1
               AND legal_id_protection_version=1
            """, keyId, AEAD_ALGORITHM).get(0, Long.class);
        long coveredAmendments = tx.fetchOne("""
            SELECT count(*) FROM merchant_amendment
             WHERE legal_id_aead_key_id=? AND legal_id_aead_algorithm=?
               AND legal_id_aad_scheme_version=1 AND legal_id_protection_version=1
            """, keyId, AEAD_ALGORITHM).get(0, Long.class);
        if (coveredMerchants != merchantCount || coveredAmendments != amendmentCount) {
            throw unavailable();
        }
    }

    private static void requireDocumentCoverage(DSLContext tx, String keyId, long documentCount) {
        long covered = tx.fetchOne("""
            SELECT count(*) FROM merchant_document
             WHERE aead_key_id=? AND aead_algorithm=?
               AND aad_scheme_version=1 AND protection_version=1
            """, keyId, AEAD_ALGORITHM).get(0, Long.class);
        if (covered != documentCount) throw unavailable();
    }

    private static void allocateNonce(
        DSLContext tx, String purpose, String keyId, String algorithm, byte[] nonce
    ) {
        int changed = tx.execute("""
            INSERT INTO merchant_protected_nonce(purpose,key_id,algorithm,nonce)
            VALUES (?,?,?,?)
            """, purpose, keyId, algorithm, nonce);
        if (changed != 1) throw unavailable();
    }

    private static void requireActiveMetadata(DSLContext tx, List<ActiveKey> keys) {
        for (ActiveKey key : keys) {
            Record active = tx.fetchOne("""
                SELECT count(*) FILTER (WHERE active),
                       count(*) FILTER (WHERE active AND key_id=? AND algorithm=?)
                  FROM merchant_registration_key_metadata WHERE key_purpose=?
                """, key.keyId(), key.algorithm(), key.purpose());
            if (!Long.valueOf(1).equals(active.get(0, Long.class))
                || !Long.valueOf(1).equals(active.get(1, Long.class))) throw unavailable();
        }
    }

    private static void ensureMetadata(DSLContext tx, String purpose, String keyId, String algorithm) {
        int changed = tx.execute("""
            INSERT INTO merchant_registration_key_metadata(
                key_purpose,key_id,algorithm,active,activated_at)
            VALUES (?,?,?,false,NULL)
            ON CONFLICT (key_purpose,key_id) DO NOTHING
            """, purpose, keyId, algorithm);
        Record exact = tx.fetchOne("""
            SELECT algorithm FROM merchant_registration_key_metadata
             WHERE key_purpose=? AND key_id=? FOR UPDATE
            """, purpose, keyId);
        if (exact == null || !algorithm.equals(exact.get(0, String.class)) || changed > 1) {
            throw unavailable();
        }
    }

    private static void activate(DSLContext tx, String purpose, String targetKeyId) {
        tx.execute("""
            UPDATE merchant_registration_key_metadata
               SET active=false
             WHERE key_purpose=? AND active AND key_id<>?
            """, purpose, targetKeyId);
        int changed = tx.execute("""
            UPDATE merchant_registration_key_metadata
               SET active=true, activated_at=statement_timestamp()
             WHERE key_purpose=? AND key_id=?
            """, purpose, targetKeyId);
        if (changed != 1) throw unavailable();
    }

    private static ProtectedRegistration protectedRegistration(Record row) {
        return new ProtectedRegistration(1,
            row.get("registration_normalization_version", Integer.class),
            row.get("registration_aead_algorithm", String.class),
            row.get("registration_aead_key_id", String.class),
            row.get("registration_nonce", byte[].class),
            row.get("registration_ciphertext", byte[].class),
            row.get("registration_auth_tag", byte[].class),
            row.get("registration_number_masked", String.class),
            new RegistrationFingerprint(1,
                row.get("registration_normalization_version", Integer.class),
                row.get("registration_fingerprint_algorithm", String.class),
                row.get("registration_search_key_id", String.class),
                row.get("registration_fingerprint", byte[].class)));
    }

    private static ProtectedPayload legalIdPayload(Record row) {
        return new ProtectedPayload(
            row.get("legal_id_aad_scheme_version", Integer.class),
            row.get("legal_id_protection_version", Integer.class),
            row.get("legal_id_aead_algorithm", String.class),
            row.get("legal_id_aead_key_id", String.class),
            row.get("legal_id_nonce", byte[].class),
            row.get("legal_id_ciphertext", byte[].class),
            row.get("legal_id_auth_tag", byte[].class),
            row.get("legal_id_no_masked", String.class));
    }

    private static ProtectedPayload documentPayload(Record row) {
        return new ProtectedPayload(
            row.get("aad_scheme_version", Integer.class),
            row.get("protection_version", Integer.class),
            row.get("aead_algorithm", String.class),
            row.get("aead_key_id", String.class),
            row.get("nonce", byte[].class),
            row.get("ciphertext", byte[].class),
            row.get("auth_tag", byte[].class), null);
    }

    private static void requireKeyId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) throw unavailable();
    }

    private static MerchantException.ProtectedFieldUnavailable unavailable() {
        return new MerchantException.ProtectedFieldUnavailable();
    }

    private record RotationCounts(long merchants, long amendments) { }

    private record ActiveKey(String purpose, String keyId, String algorithm) { }
}
