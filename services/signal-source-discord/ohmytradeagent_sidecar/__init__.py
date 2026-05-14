"""Discord signal source for oh-my-tradeagent.

Watches a vetted Discord channel, parses BTO/STC/AVG lines, and starts a
CopytradeSignalWorkflow on the local Temporal cluster keyed by signal_id
so duplicate posts are durably deduped by Temporal's
WorkflowIDReusePolicy=REJECT_DUPLICATE.

Replica >= 1 is supported; the in-memory LRU is a cost optimization only,
not a correctness layer.
"""
