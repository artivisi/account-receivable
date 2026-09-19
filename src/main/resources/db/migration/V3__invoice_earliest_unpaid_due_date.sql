-- Denormalized collection-due date on invoice: the earliest still-unpaid due date of a receivable.
--   * single-payment invoice  -> its own due_date while it still owes
--   * installment invoice      -> the earliest unpaid installment's due_date
--   * fully paid / written off -> NULL
--
-- "Overdue" is a derived concept (for installment invoices it depends on the child installment rows),
-- which previously forced the admin invoice list to load every invoice into memory and derive overdue
-- per row. Storing the earliest unpaid due date turns overdue into a pure, indexable SQL predicate
-- (earliest_unpaid_due_date < today) so the list can filter, sort, and paginate in the database.
-- The column stores a DATE (not an overdue flag), so it never goes stale from the passage of time —
-- only from data mutations, which the service layer maintains via Invoice.recomputeEarliestUnpaidDueDate().

alter table invoice add column earliest_unpaid_due_date date;

-- Backfill: single-payment invoices that still owe.
update invoice
   set earliest_unpaid_due_date = due_date
 where is_installment = false
   and outstanding > 0
   and payment_status <> 'WRITTEN_OFF';

-- Backfill: installment invoices -> earliest unpaid installment due date.
update invoice i
   set earliest_unpaid_due_date = sub.min_due
  from (
        select ps.id_invoice as id_invoice, min(inst.due_date) as min_due
          from installment inst
          join payment_schedule ps on inst.id_schedule = ps.id
         where inst.outstanding > 0
           and inst.payment_status <> 'WRITTEN_OFF'
         group by ps.id_invoice
       ) sub
 where i.id = sub.id_invoice
   and i.is_installment = true
   and i.payment_status <> 'WRITTEN_OFF';

-- Partial index: only open (non-null) rows are indexed, so overdue lookups scan just the collectible set.
create index idx_invoice_earliest_unpaid_due
    on invoice (earliest_unpaid_due_date)
 where earliest_unpaid_due_date is not null;
