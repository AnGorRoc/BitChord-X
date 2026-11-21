# BitChord Listen Together — container image.
#
# Built by Coolify (or plain `docker build`) from this directory. Parties live in
# memory, so run exactly one container of it: two would hold two disjoint sets
# of parties behind the same address.

FROM golang:1.27-alpine AS build
WORKDIR /src
COPY . .
RUN go mod tidy && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/server .

FROM alpine:3.22
RUN apk add --no-cache ca-certificates tzdata \
    && adduser -D -H -u 10001 jam \
    && mkdir -p /data/feedback && chown -R jam:jam /data
COPY --from=build /out/server /usr/local/bin/bitchord-jam
USER jam
ENV PORT=8000
# Feedback attachments. Mount a persistent volume here, or every redeploy loses them.
ENV FEEDBACK_DIR=/data/feedback
ENV ADMIN_PORT=8001
EXPOSE 8000 8001
# busybox wget is in the base image; /healthz answers {"ok":true}.
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD wget -qO- "http://127.0.0.1:${PORT}/healthz" | grep -q '"ok":true' || exit 1
ENTRYPOINT ["/usr/local/bin/bitchord-jam"]
