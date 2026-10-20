import { execSync } from 'node:child_process';

/** Makes sure the throw-away database never outlives the test run, even if the launcher was killed abruptly. */
export default async function teardown() {
  try {
    execSync('docker rm -f jm-e2e-db', { stdio: 'ignore' });
  } catch {
    /* already removed */
  }
}
