-- The gateway allocates VA numbers now; AR sends it two keys and stores what comes back.
--
-- payer_key and slot_key are the keys a charge's number was allocated from. AR needs them on the
-- row because the one question it used to answer with a composed number — is another invoice of
-- this debtor and type still collecting? — can no longer be asked that way: AR does not know the
-- number until the gateway has answered.
--
-- interbank_va_number is the form a payer uses from another bank, composed by the gateway and
-- stored as reported, because a payer is told it once and a later prefix change must not rewrite
-- what past charges said. This migration leaves it NULL on every existing charge: the prefix is a
-- deployment fact this file must not carry. A deployment backfills it at the switch, from the same
-- prefix AR composed it with until now, because announcement.requested re-announces a live charge
-- and charge.opened refuses to go out without it.
alter table charge add column payer_key varchar(64);
alter table charge add column slot_key varchar(16);
alter table charge add column interbank_va_number varchar(64);

-- Backfill from AR's own tables: the debtor, and the type's VA code. That is exactly what the
-- number used to be composed from, so an old charge and a new one for the same debtor and type
-- carry the same keys. A charge whose type has no VA code keeps a NULL slot_key; it could not have
-- been opened through the composer either, so only migrated history is in that state.
update charge c
   set payer_key = d.code, slot_key = m.va_code
  from invoice i
  join debtor d on d.id = i.id_debtor
  left join invoice_type_va_code m on m.id_invoice_type = i.id_invoice_type
 where c.id_invoice = i.id;

update charge c
   set payer_key = d.code, slot_key = m.va_code
  from installment ins
  join payment_schedule ps on ps.id = ins.id_schedule
  join invoice i on i.id = ps.id_invoice
  join debtor d on d.id = i.id_debtor
  left join invoice_type_va_code m on m.id_invoice_type = i.id_invoice_type
 where c.id_installment = ins.id;

create index idx_charge_allocation_keys on charge (payer_key, slot_key);
