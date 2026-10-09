-- Two contract commands the upstream application teams asked for, and the uniqueness fix that
-- goes with them.
--
-- payment.recorded carries a payment taken outside the Payment Gateway: cash at the counter, a
-- direct transfer, or a QRIS acquirer. Until now there was no way to tell AR about one, so those
-- receivables stayed open for ever while the money sat in the bank.
--
-- payment.reversed carries a reversal back to the upstream application. AR has reversed payments
-- since V1 (CollectionService.reversePayment) but never told anyone, so an applicant whose payment
-- finance reversed still reads as settled upstream, permanently.

-- 1. A payment reference is only unique inside the namespace that issued it.
--
-- The column was named for the gateway because the gateway was the only source. An upstream
-- receipt number and a bank journal number are issued by different parties, so they can collide by
-- coincidence, and a global unique index turns that coincidence into a lost payment: the second
-- one is rejected as a replay of the first. Scope the uniqueness to the source instead.
alter table cash_application rename column gateway_payment_reference to payment_reference;

alter table cash_application add column source varchar(20);
-- Every row that exists today arrived through the gateway webhook, including the ones the anomaly
-- review booked from a bank finding — those carry the bank's own reference, which is the gateway's
-- namespace too.
update cash_application set source = 'GATEWAY' where source is null;
alter table cash_application alter column source set not null;

alter table cash_application drop constraint cash_application_gateway_payment_reference_key;
alter table cash_application
    add constraint uq_cash_application_source_reference unique (source, payment_reference);

-- 2. What a reversal was, beyond the fact that it happened.
--
-- The status has carried REVERSED since V1, but nothing recorded when, why, or on whose authority —
-- so a reversal could not be reported to the upstream application or audited afterwards.
alter table cash_application add column reversed_at         timestamptz;
alter table cash_application add column reversal_reason     varchar(255);
alter table cash_application add column reversal_reference  varchar(128);

comment on column cash_application.source is
    'Namespace that issued payment_reference: GATEWAY (bank, through the gateway webhook) or RECORDED (an upstream application, through payment.recorded).';
comment on column cash_application.reversal_reference is
    'Approval number the reversal rests on, when a person decided it upstream. Same meaning as decisionReference in the v2 contract.';
