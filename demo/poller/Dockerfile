# syntax=docker/dockerfile:1.7
#
# The public demo's POLLER image: two files and the Node runtime, no dependencies. Build context is
# the REPO ROOT. It holds the preview token and talks only to the BFF; it serves nothing.

# Pinned by digest; see the note in demo/Dockerfile.
FROM node:22.23.5-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a
WORKDIR /app
COPY demo/poller/check.mjs demo/poller/poller.mjs ./
USER node
ENV NODE_ENV=production
CMD ["node", "poller.mjs"]
