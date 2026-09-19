-- Legacy debtor records hold more than one contact number in a single field, labelled by hand:
--   081200000001(wa)081200000002(TLP)
-- That is 33 characters against a 32-character column. Truncating would leave a phone number that
-- is not merely incomplete but WRONG — it would dial the first number missing its last digits — so
-- the column is widened to fit the data as recorded rather than silently corrupting it.
alter table debtor alter column phone type varchar(64);
