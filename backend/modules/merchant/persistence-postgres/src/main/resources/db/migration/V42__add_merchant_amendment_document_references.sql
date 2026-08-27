DO $$
BEGIN
    IF to_regclass('public.merchant_amendment_document') IS NOT NULL THEN
        RAISE EXCEPTION 'V42 blocked: merchant_amendment_document already exists';
    END IF;
    IF to_regclass('public.merchant_amendment') IS NULL
       OR to_regclass('public.merchant_document') IS NULL
       OR to_regclass('public.merchant_document_binding') IS NULL THEN
        RAISE EXCEPTION 'V42 blocked: canonical MCH-003 document tables are absent';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM merchant_amendment amendment
         WHERE amendment.status IN ('PENDING_REVIEW','REJECTED','STALE')
           AND 5 <> (
             SELECT count(*)
               FROM merchant_document document
              WHERE document.amendment_id=amendment.id
                AND document.merchant_id=amendment.merchant_id
                AND document.target_tenant_id=amendment.target_tenant_id
                AND document.attachment_scope='AMENDMENT'
                AND document.deleted_at IS NULL)
    ) THEN
        RAISE EXCEPTION 'V42 blocked: historical amendment document evidence is incomplete';
    END IF;
END
$$;

CREATE TABLE merchant_amendment_document (
    amendment_id BIGINT NOT NULL REFERENCES merchant_amendment(id),
    kind VARCHAR(32) NOT NULL,
    document_id BIGINT NOT NULL REFERENCES merchant_document(id),
    document_mode VARCHAR(8) NOT NULL,
    PRIMARY KEY (amendment_id,kind),
    CONSTRAINT uk_merchant_amendment_document_id UNIQUE (amendment_id,document_id),
    CONSTRAINT ck_merchant_amendment_document_kind CHECK (kind IN (
        'BRAND_LOGO','BUSINESS_LICENSE','LEGAL_ID_FRONT','LEGAL_ID_BACK','LEGAL_ID_HOLDING')),
    CONSTRAINT ck_merchant_amendment_document_mode CHECK (
        document_mode IN ('RETAIN','REPLACE'))
);

INSERT INTO merchant_amendment_document(amendment_id,kind,document_id,document_mode)
SELECT amendment_id,kind,id,'REPLACE'
  FROM merchant_document
 WHERE amendment_id IS NOT NULL
   AND attachment_scope='AMENDMENT'
   AND deleted_at IS NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM merchant_amendment amendment
         WHERE amendment.status='PENDING_REVIEW'
           AND 5 <> (
             SELECT count(*)
               FROM merchant_amendment_document reference
              WHERE reference.amendment_id=amendment.id)
    ) THEN
        RAISE EXCEPTION 'V42 blocked: pending amendment document references are incomplete';
    END IF;
END
$$;
