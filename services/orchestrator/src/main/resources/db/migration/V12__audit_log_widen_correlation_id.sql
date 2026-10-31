-- Issue #1029: FloorBreachAlertLoop copies the PositionWorkflow id into correlation_id; a
-- Discord-derived id is 111 chars (e.g.
--   t-staging_paper/s-copytrade-v1/pos/SMCI  261120C00050000/
--   chat-messages-769797179992571914-1541869380974157876:0)
-- which overflows the VARCHAR(96) declared in V2 and silently drops the floor-breach page.
-- 256 = workflow_id's own width, so anything storable there is storable here.
ALTER TABLE audit_log ALTER COLUMN correlation_id TYPE VARCHAR(256);
