# syntax=docker/dockerfile:1.7
#
# The public demo's POLLER image: two files and the Node runtime, no dependencies. Build context is
# the REPO ROOT. It holds the preview token and talks only to the BFF; it serves nothing.

FROM node:22-alpine
WORKDIR /app
COPY demo/poller/check.mjs demo/poller/poller.mjs ./
USER node
ENV NODE_ENV=production
CMD ["node", "poller.mjs"]
