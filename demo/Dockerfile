# syntax=docker/dockerfile:1.7
#
# The public demo's WEB image: the statically exported site served by unprivileged nginx. Build
# context is the REPO ROOT (as for dashboard/Dockerfile): the demo renders the dashboard's own view
# components, so the build needs dashboard/components and dashboard/lib beside demo/.
#
# Dependencies are installed one level ABOVE both source trees so that files under dashboard/ and
# files under demo/ resolve the same single copy of React and Next.

# Base images are pinned by digest: this pod is internet-facing, and a tag can be re-pointed. To
# update, pick the new tag, read its digest from the registry, and change both together (the same
# node pin is in demo/poller/Dockerfile). nginx follows the STABLE branch (1.30); 1.27 was a
# retired mainline branch that no longer got fixes.
FROM node:22.23.5-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a AS deps
WORKDIR /src
COPY demo/package.json demo/package-lock.json ./
RUN npm ci

FROM node:22.23.5-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a AS builder
WORKDIR /src
COPY --from=deps /src/node_modules ./node_modules
COPY dashboard/components ./dashboard/components
COPY dashboard/lib ./dashboard/lib
COPY dashboard/app/globals.css ./dashboard/app/globals.css
COPY dashboard/tailwind.config.ts ./dashboard/tailwind.config.ts
COPY demo/ ./demo/
ENV NEXT_TELEMETRY_DISABLED=1
WORKDIR /src/demo
# The unit tests gate the image: the adapters and the poller's publish gate must pass to build.
RUN node --experimental-strip-types --test lib/*.test.mjs poller/*.test.mjs
RUN ../node_modules/.bin/next build

FROM nginxinc/nginx-unprivileged:1.30.6-alpine@sha256:f5116ed4606f3eb015dd4a8e28cb9b351476dc0ad4bc6e9f93b19cec130cdf03 AS runner
COPY demo/nginx.conf /etc/nginx/nginx.conf
COPY --from=builder /src/demo/out /usr/share/nginx/html
# Fail the build on a config error rather than at pod start.
RUN nginx -t -c /etc/nginx/nginx.conf
EXPOSE 8080
