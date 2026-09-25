-- What kind of correction a credit note is, and the decision it rests on.
--
-- Until now the only thing distinguishing a scholarship from a pricing correction was free text, so
-- neither could be reported: "how much did we award this term" and "how much did we misprice" are
-- different questions that finance has to answer separately, and a reviewer reading a one-line reason
-- cannot tell which they are looking at.
--
-- reference is the document the credit rests on — a scholarship decree, an approval, a ticket. It is
-- required for SCHOLARSHIP because an award nobody can trace back to a decision is indistinguishable
-- from a mistake; the service enforces that, not a constraint, so an operator gets an error they can
-- read rather than a violation.
--
-- Both columns are nullable because a note issued before this existed has no honest value to put in
-- them. NULL means "issued before the kind was recorded", and reports show it as such; it is never a
-- default for a new note, which the service refuses without a kind.
alter table credit_note add column reason_code varchar(32);
alter table credit_note add column reference   varchar(128);
