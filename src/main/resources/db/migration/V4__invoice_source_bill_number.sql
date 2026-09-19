-- Bill number in an originating system when the invoice is mirrored from one (a legacy billing
-- application, via a bridge). Threaded to the gateway charge's bill_number so the bank adapter and
-- any reverse payment bridge carry the number the originating system knows. Null for AR-native
-- issues.
alter table invoice add column source_bill_number varchar(128);
