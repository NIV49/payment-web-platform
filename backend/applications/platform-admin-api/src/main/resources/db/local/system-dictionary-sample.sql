-- Local-only browser sample. Seed only when this exact sample type has never
-- existed so unrelated global dictionaries and user tombstones stay authoritative.
WITH inserted_type AS (
    INSERT INTO sys_dictionary_type(
        id,dict_type,dict_name,sort_order,remark
    )
    SELECT nextval('iam_id_seq'),'BELONG_SYSTEM','系统-归属系统',0,''
     WHERE NOT EXISTS (
         SELECT 1 FROM sys_dictionary_type WHERE dict_type = 'BELONG_SYSTEM'
     )
    RETURNING id
), inserted_data AS (
    INSERT INTO sys_dictionary_data(
        id,dictionary_type_id,label,value,color,sort_order,remark
    )
    SELECT nextval('iam_id_seq'),inserted_type.id,sample.label,sample.value,
           sample.color,sample.sort_order,''
      FROM inserted_type
      CROSS JOIN (VALUES
          ('运维','1','processing',1),
          ('商户','2','success',2),
          ('代理','3','purple',3)
      ) sample(label,value,color,sort_order)
    RETURNING id
)
UPDATE sys_dictionary_catalog_revision
   SET revision=revision+1,updated_at=now()
 WHERE singleton_id=1
   AND EXISTS (SELECT 1 FROM inserted_data);
