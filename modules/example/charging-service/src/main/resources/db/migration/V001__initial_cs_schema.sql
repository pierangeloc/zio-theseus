create table charge_session (
  id uuid primary key not null,
  charge_point_id varchar not null,
  charge_card_id varchar not null,
  started_at timestamptz not null,
  ended_at timestamptz
);

