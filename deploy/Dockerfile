# syntax=docker/dockerfile:1
FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY . .
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/server ./cmd/server && \
    CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/loadgen ./cmd/loadgen

FROM alpine:3.20
RUN apk add --no-cache ca-certificates curl && adduser -D -u 10001 app
WORKDIR /app
COPY --from=build /out/server /out/loadgen /usr/local/bin/
COPY configs/limits.yaml /app/configs/limits.yaml
USER app
EXPOSE 8080
ENTRYPOINT ["server"]
