# syntax=docker/dockerfile:1.7
#
# The public demo's WEB image: the statically exported site served by unprivileged nginx. Build
# context is the REPO ROOT (as for dashboard/Dockerfile): the demo renders the dashboard's own view
# components, so the build needs dashboard/components and dashboard/lib beside demo/.
#
# Dependencies are installed one level ABOVE both source trees so that files under dashboard/ and
# files under demo/ resolve the same single copy of React and Next.

FROM node:22-alpine AS deps
WORKDIR /src
COPY demo/package.json demo/package-lock.json ./
RUN npm ci

FROM node:22-alpine AS builder
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

FROM nginxinc/nginx-unprivileged:1.27-alpine AS runner
COPY demo/nginx.conf /etc/nginx/nginx.conf
COPY --from=builder /src/demo/out /usr/share/nginx/html
# Fail the build on a config error rather than at pod start.
RUN nginx -t -c /etc/nginx/nginx.conf
EXPOSE 8080
