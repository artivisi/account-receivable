-- What the bank recorded, for a finding whose remedy is booking a payment.
--
-- WHY STRUCTURED, WHEN V12 KEPT THE FINDING AS PROSE
--
-- A BANK_PAID_NOT_BOOKED finding says the bank received money that no book recorded, so the student
-- is still billed for it. The only correct remedy is a ledger entry, and a ledger entry needs an
-- amount, a moment, the VA it was paid into and the bank that took it. Until now those lived only in
-- `detail`, as a sentence a person read and then re-typed into whatever booked the payment. Re-typing
-- money is exactly where a book goes wrong without anyone noticing: 2.450.000 keyed as 2.540.000
-- balances perfectly against itself.
--
-- So the finding carries the bank's own figures, and booking reads them from here. The operator
-- confirms that the bank's record matches the finding; the operator never supplies the figures.
-- evidence_ref (V12) is already the bank reference and stays the idempotency key of the booking.
--
-- ALL NULLABLE
--
-- Most categories have no payment behind them (a divergence spotted across two systems, a bill the
-- source withdrew), and a finding raised before this migration has its figures only in prose. A
-- missing figure makes a finding unbookable and says so on screen; it never gets a default. The
-- VA number and bank are separate columns because payment.received requires both, and a bank's
-- transaction report is the only source that states them for money no book recorded.
alter table receivable_anomaly add column evidence_amount    numeric(19,2);
alter table receivable_anomaly add column evidence_at        timestamptz;
alter table receivable_anomaly add column evidence_va_number varchar(64);
alter table receivable_anomaly add column evidence_bank      varchar(64);
