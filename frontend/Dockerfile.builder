# The portal builder (widget workbench) for portalbuilder.afriqpay.com: build, then static nginx.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install --no-audit --no-fund
COPY . .
RUN npx ng build widget-workbench --configuration production && mv dist/widget-workbench/browser /site

FROM nginx:1.27-alpine
COPY projects/widget-workbench/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /site /usr/share/nginx/html
