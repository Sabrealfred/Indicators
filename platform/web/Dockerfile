FROM node:22-slim
WORKDIR /app
COPY package.json package-lock.json ./
COPY api/package.json api/
COPY web/package.json web/
RUN npm ci --workspace web --include-workspace-root=false
COPY web web
WORKDIR /app/web
ARG API_URL=http://api:4000
ENV API_URL=$API_URL
RUN npx next build
EXPOSE 3000
CMD ["npx", "next", "start", "-p", "3000"]
