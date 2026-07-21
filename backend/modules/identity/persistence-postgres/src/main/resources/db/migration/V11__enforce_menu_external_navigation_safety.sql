-- Vben consumes link and iframeSrc as executable browser navigation targets.
-- Install the constraint as NOT VALID first so PostgreSQL distinguishes legacy
-- preflight validation from enforcement of all new writes. Flyway wraps both
-- statements in one transaction: a dirty historical row rolls V11 back fully.
--
-- Keep the URL grammar aligned with VbenMenuContract.ABSOLUTE_HTTP_URL:
-- absolute HTTP(S), an explicit ASCII/punycode or bracketed-IP host, optional
-- numeric port, and no whitespace/control characters.
ALTER TABLE iam_menu
    ADD CONSTRAINT ck_iam_menu_external_navigation_safety
    CHECK ((
        jsonb_typeof(meta_json) = 'object'
        AND CASE menu_type
            WHEN 'EMBEDDED' THEN
                NOT (meta_json ? 'link')
                AND jsonb_typeof(meta_json -> 'iframeSrc') = 'string'
                AND (meta_json ->> 'iframeSrc') ~*
                    '^https?://([A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?|\[[0-9A-Fa-f:.]+\])(:[0-9]{1,5})?([/?#][^[:cntrl:][:space:]]*)?$'
            WHEN 'LINK' THEN
                NOT (meta_json ? 'iframeSrc')
                AND jsonb_typeof(meta_json -> 'link') = 'string'
                AND (meta_json ->> 'link') ~*
                    '^https?://([A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?|\[[0-9A-Fa-f:.]+\])(:[0-9]{1,5})?([/?#][^[:cntrl:][:space:]]*)?$'
            ELSE
                NOT (meta_json ? 'iframeSrc') AND NOT (meta_json ? 'link')
        END
    ) IS TRUE) NOT VALID;

ALTER TABLE iam_menu
    VALIDATE CONSTRAINT ck_iam_menu_external_navigation_safety;
