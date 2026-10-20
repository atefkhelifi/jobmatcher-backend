// Starts a throw-away PostgreSQL container and the packaged API against it (mock AI, stubbed job source).
// Build the jar first:  cd backend && ./mvnw -DskipTests package
import { execSync, spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const jar = join(here, '..', '..', 'backend', 'target', 'backend-0.2.0.jar');
const NAME = 'jm-e2e-db';
const DB_PORT = 55433;

if (!existsSync(jar)) {
  console.error(`Missing ${jar}. Run: cd backend && ./mvnw -DskipTests package`);
  process.exit(1);
}

const sh = (cmd) => execSync(cmd, { stdio: 'pipe' }).toString();
try {
  sh(`docker rm -f ${NAME}`);
} catch {
  /* not running */
}
sh(`docker run -d --rm --name ${NAME} -p ${DB_PORT}:5432 -e POSTGRES_DB=jobmatcher -e POSTGRES_USER=jobmatcher -e POSTGRES_PASSWORD=e2e postgres:17-alpine`);

// The image restarts once while initialising, so wait until a real query works twice in a row.
let ok = 0;
for (let i = 0; i < 90 && ok < 2; i++) {
  try {
    sh(`docker exec ${NAME} psql -U jobmatcher -d jobmatcher -tAc "select 1"`);
    ok++;
  } catch {
    ok = 0;
  }
  await new Promise((r) => setTimeout(r, 700));
}
if (ok < 2) {
  console.error('PostgreSQL did not become ready');
  process.exit(1);
}

const api = spawn('java', ['-jar', jar], {
  stdio: 'inherit',
  env: {
    ...process.env,
    SERVER_PORT: '8081',
    DB_URL: `jdbc:postgresql://127.0.0.1:${DB_PORT}/jobmatcher`,
    DB_USER: 'jobmatcher',
    DB_PASSWORD: 'e2e',
    APP_ENCRYPTION_KEY: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=',
    AI_PROVIDER: 'mock',
    APP_JOBS_KEEJOB_ENABLED: 'false', // the journey must not call real sites
    APP_JOBS_ARBEITNOW_ENDPOINT: 'http://127.0.0.1:4010/api/job-board-api',
    APP_FETCH_ALLOW_PRIVATE: 'true', // only because the stub job source lives on 127.0.0.1
    APP_FETCH_MIN_INTERVAL: '0s',
    APP_JOBS_SCHEDULING_ENABLED: 'false',
    APP_RATE_LIMIT_LOGIN_PER_MINUTE: '1000',
    APP_RATE_LIMIT_REGISTER_PER_HOUR: '1000',
  },
});

const stop = () => {
  api.kill();
  try {
    sh(`docker rm -f ${NAME}`);
  } catch {
    /* already gone */
  }
};
process.on('exit', stop);
process.on('SIGINT', () => process.exit(0));
process.on('SIGTERM', () => process.exit(0));
api.on('exit', (code) => process.exit(code ?? 0));
