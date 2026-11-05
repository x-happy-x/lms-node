-- migrate:up
CREATE TABLE public.jobs (id text PRIMARY KEY, snapshot jsonb NOT NULL);

-- migrate:down
-- Restore the application backup; this baseline has no destructive automatic rollback.
