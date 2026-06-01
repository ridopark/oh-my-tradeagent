# syntax=docker/dockerfile:1.7
#
# Next.js dashboard image. Build context is the REPO ROOT (matches build-images.yml `context: .`,
# same convention as services/signal-source-discord/Dockerfile), so all COPY paths are dashboard/*.
# Multi-stage: install deps, build the standalone server bundle (next.config: output 'standalone'),
# then ship a slim runtime. This is the Node build — NOT the shared Java Dockerfile.

FROM node:22-alpine AS deps
WORKDIR /app
COPY dashboard/package.json dashboard/package-lock.json* ./
RUN npm ci

FROM node:22-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY dashboard/ ./
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:22-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
RUN addgroup --system --gid 1001 nodejs \
  && adduser --system --uid 1001 nextjs
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static
COPY --from=builder --chown=nextjs:nodejs /app/public ./public
USER 1001
EXPOSE 3000
ENV PORT=3000
ENV HOSTNAME=0.0.0.0
CMD ["node", "server.js"]
