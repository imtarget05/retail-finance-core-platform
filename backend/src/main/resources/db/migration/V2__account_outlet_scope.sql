-- V1 did not record account ownership or the actor's outlet at opening time.
-- The old opening movement used the arbitrary first outlet. Historical user
-- assignments may also have changed. No V1 row is safe to auto-attribute.
-- Leave every historical account unresolved (NULL) and deny POS access until
-- a separately approved, source-verified ownership assignment is performed.
ALTER TABLE account ADD COLUMN outlet_id BIGINT NULL;
ALTER TABLE account ADD KEY idx_account_outlet (outlet_id);
ALTER TABLE account ADD CONSTRAINT fk_account_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id);
