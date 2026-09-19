-- A notification's source id is not always a UUID.
--
-- V2 sized this column at 36 because every source id then was one: an invoice id, an installment id,
-- or a bank payment reference. A charge reopened after supersession carries a generation suffix
-- (<invoiceId>#2, 38 chars), so the bill-ready notification for it could not be enqueued — and
-- because that insert happens inside the transaction that opens the charge, the whole reopen rolled
-- back. On 2026-08-25 that failed all nine repairs with a 500 AFTER the gateway had already created
-- their charges, leaving nine live VAs the books knew nothing about.
--
-- Widened to match charge.consumer_reference (128), which is what this column actually holds for
-- invoice-issued notifications. A source id is an identifier from somewhere else; sizing it to the
-- shape of today's identifiers is what made it a landmine.

alter table notification_outbox alter column source_id type varchar(128);
