DO $$
BEGIN
    IF to_regclass('public.merchant_amendment_document') IS NULL
       OR to_regprocedure('merchant_amendment_document_guard()') IS NOT NULL
       OR EXISTS (SELECT 1 FROM pg_trigger
                   WHERE tgrelid='merchant_amendment_document'::regclass
                     AND tgname='trg_merchant_amendment_document_guard'
                     AND NOT tgisinternal) THEN
        RAISE EXCEPTION 'V43 blocked: amendment document reference boundary is absent or modified';
    END IF;
    IF 4 <> (SELECT count(*) FROM information_schema.columns
              WHERE table_schema='public' AND table_name='merchant_amendment_document'
                AND is_nullable='NO'
                AND ((column_name='amendment_id' AND data_type='bigint')
                  OR (column_name='kind' AND data_type='character varying')
                  OR (column_name='document_id' AND data_type='bigint')
                  OR (column_name='document_mode' AND data_type='character varying'))) THEN
        RAISE EXCEPTION 'V43 blocked: amendment document reference columns are absent or modified';
    END IF;
END
$$;

LOCK TABLE merchant_amendment,merchant_document,merchant_document_binding,
           merchant_amendment_document IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM merchant_amendment amendment
         WHERE amendment.status='PENDING_REVIEW'
           AND 5 <> (SELECT count(*) FROM merchant_amendment_document reference
                       WHERE reference.amendment_id=amendment.id)
    ) OR EXISTS (
        SELECT 1
          FROM merchant_amendment_document reference
          JOIN merchant_amendment amendment ON amendment.id=reference.amendment_id
          JOIN merchant_document document ON document.id=reference.document_id
          LEFT JOIN merchant_document_binding binding
            ON binding.merchant_id=amendment.merchant_id
           AND binding.kind=reference.kind AND binding.document_id=document.id
         WHERE document.kind<>reference.kind
            OR document.target_tenant_id<>amendment.target_tenant_id
            OR (reference.document_mode='REPLACE' AND (
                 amendment.status NOT IN ('PENDING_REVIEW','REJECTED','STALE')
                 OR document.attachment_scope<>'AMENDMENT'
                 OR document.amendment_id<>amendment.id
                 OR document.merchant_id<>amendment.merchant_id))
            OR (reference.document_mode='RETAIN' AND (
                 amendment.status<>'PENDING_REVIEW'
                 OR document.attachment_scope<>'MERCHANT'
                 OR document.merchant_id<>amendment.merchant_id
                 OR binding.document_id IS NULL))
    ) THEN
        RAISE EXCEPTION 'V43 blocked: amendment document reference evidence is inconsistent';
    END IF;
END
$$;

CREATE FUNCTION merchant_amendment_document_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    amendment_row merchant_amendment%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'merchant amendment document references are append-only';
    END IF;

    SELECT * INTO amendment_row FROM merchant_amendment
     WHERE id=NEW.amendment_id FOR KEY SHARE;
    IF NOT FOUND OR amendment_row.status<>'PENDING_REVIEW' THEN
        RAISE EXCEPTION 'merchant amendment document reference requires a pending amendment';
    END IF;

    IF NEW.document_mode='REPLACE' THEN
        IF NOT EXISTS (
            SELECT 1 FROM merchant_document document
             WHERE document.id=NEW.document_id
               AND document.kind=NEW.kind
               AND document.target_tenant_id=amendment_row.target_tenant_id
               AND document.merchant_id=amendment_row.merchant_id
               AND document.amendment_id=amendment_row.id
               AND document.attachment_scope='AMENDMENT'
               AND document.deleted_at IS NULL) THEN
            RAISE EXCEPTION 'replacement amendment document reference is inconsistent';
        END IF;
    ELSIF NEW.document_mode='RETAIN' THEN
        IF NOT EXISTS (
            SELECT 1
              FROM merchant_document_binding binding
              JOIN merchant_document document ON document.id=binding.document_id
             WHERE binding.merchant_id=amendment_row.merchant_id
               AND binding.kind=NEW.kind AND binding.document_id=NEW.document_id
               AND document.kind=NEW.kind
               AND document.target_tenant_id=amendment_row.target_tenant_id
               AND document.merchant_id=amendment_row.merchant_id
               AND document.attachment_scope='MERCHANT'
               AND document.deleted_at IS NULL) THEN
            RAISE EXCEPTION 'retained amendment document reference is inconsistent';
        END IF;
    ELSE
        RAISE EXCEPTION 'unknown amendment document reference mode';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_merchant_amendment_document_guard
BEFORE INSERT OR UPDATE OR DELETE ON merchant_amendment_document
FOR EACH ROW EXECUTE FUNCTION merchant_amendment_document_guard();
