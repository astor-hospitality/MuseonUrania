-- Корректный адрес новости; прежний URL перенаправляется в next.config.ts.
update news_item set slug = 'zdravookhranenie-2026', updated_at = now(), version = version + 1
where id = '203c9b18-d6dc-43ef-a848-deba28a3cd67' and slug = 'aran-2026';

insert into audit_entry (id, actor, action, subject, subject_id, correlation_id, payload)
values ('a4c89b70-f640-4d3d-a47a-ce22a6202bee', 'system:migration', 'news.update', 'news', 'zdravookhranenie-2026', 'migration:v48',
 jsonb_build_object('old_slug', 'aran-2026', 'new_slug', 'zdravookhranenie-2026'))
on conflict (id) do nothing;
