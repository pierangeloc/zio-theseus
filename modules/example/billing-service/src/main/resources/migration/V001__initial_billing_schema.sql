create table billable_session (
  id uuid primary key not null,
  customer varchar not null,
  tariff_id varchar not null,
  price_per_minute numeric(10, 2) not null,
  started_at timestamptz not null,
  ended_at timestamptz,
  total_price numeric(10, 2)
);
