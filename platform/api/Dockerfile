FROM node:22-slim
WORKDIR /app
COPY package.json package-lock.json ./
COPY api/package.json api/
COPY web/package.json web/
RUN npm ci --workspace api --include-workspace-root=false
COPY api api
WORKDIR /app/api
RUN npx tsc -p tsconfig.json
ENV DB_PATH=/data/ledgerbank.db PORT=4000
VOLUME /data
EXPOSE 4000
CMD ["node", "--no-warnings", "dist/server.js"]
