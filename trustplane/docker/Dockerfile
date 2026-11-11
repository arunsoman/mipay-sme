# syntax=docker/dockerfile:1
ARG GO_VERSION=1.25
ARG SERVICE

FROM golang:${GO_VERSION}-alpine AS build
ARG SERVICE
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/app ./cmd/${SERVICE}

FROM alpine:3.20
RUN apk add --no-cache curl && adduser -D -H -u 10001 app
COPY --from=build /out/app /app/app
USER 10001
EXPOSE 8443
ENTRYPOINT ["/app/app"]
