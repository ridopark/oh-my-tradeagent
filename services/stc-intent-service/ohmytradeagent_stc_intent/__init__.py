"""STC close-intent classifier service.

The P0 ``/classify`` backend for PLAN-2026-07-25-stc-intent-classifier: a small,
torch-free (fastembed / onnxruntime) semantic classifier that decides whether the
free-text tail of an STC line intends a FULL exit or a PARTIAL.
"""
