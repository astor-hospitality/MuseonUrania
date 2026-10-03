-- Customer supplied a new A-2000 presentation on 3 October 2026. The object
-- keeps the stable storage key; this migration records the new revision and
-- tells Vedalina to rebuild the document from the replaced PDF.

update document
set file_size = 723296,
    revision = 'редакция заказчика от 03.10.2026',
    approved_by = 'ООО «ВЕДАЛ»',
    updated_at = now(),
    version = version + 1
where slug = 'vedal-a-2000-product-sheet';

insert into audit_entry (
    id, actor, action, subject, subject_id, correlation_id, payload
) values (
    '2cff5558-7e5c-4e2a-932f-5becd53dd9ba',
    'system:migration',
    'document.upload',
    'document',
    'vedal-a-2000-product-sheet',
    'migration:v45',
    jsonb_build_object('size', 723296, 'published', true)
);

insert into outbox (
    id, aggregate, aggregate_id, type, payload, correlation_id
) values (
    'ec2a474f-4de5-43b9-85e2-8513c443bd0c',
    'document',
    'vedal-a-2000-product-sheet',
    'vedal.documents.v1',
    jsonb_build_object(
        'action', 'file-replaced',
        'slug', 'vedal-a-2000-product-sheet',
        'group', 'Техническая документация'
    ),
    'migration:v45'
);
