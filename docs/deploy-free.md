# Publishing for free, without a card

The whole stack runs from your own machine, and a Cloudflare quick tunnel gives it a public HTTPS address. No account, domain or card.

```bash
cd infra
cp .env.example .env        # set DB_PASSWORD and APP_ENCRYPTION_KEY (openssl rand -base64 32)
# in .env: COOKIE_SECURE=true
docker compose --env-file .env --profile tunnel up -d --build
docker compose logs tunnel  # look for https://<random-words>.trycloudflare.com
```

Stop publishing with `docker compose --profile tunnel stop tunnel` (the app stays available on `http://localhost:8088`).

## A permanent address with Tailscale Funnel

Funnel gives your machine a fixed `https://<pc-name>.<tailnet>.ts.net` address with a real certificate, with no domain, card or router changes.

1. Install Tailscale (https://tailscale.com/download) and sign in. A free personal account is enough.
2. In the admin console, turn on **HTTPS Certificates** (DNS page) and allow **Funnel** for the device.
3. Publish the web container, which listens on port 8088:

   ```bash
   tailscale funnel --bg 8088
   tailscale funnel status      # prints the public address
   ```

4. Keep `COOKIE_SECURE=true` in `infra/.env`, and stop the temporary quick tunnel: `docker compose --profile tunnel stop tunnel`.

Stop publishing with `tailscale funnel reset`. The address survives restarts, but the site is only up while the machine is on and Docker is running.

## Limits

- The address changes every time the tunnel container restarts, and it only works while your computer and Docker are running.
- Quick tunnels have no uptime guarantee. They suit a demo or personal use, not a service other people depend on.
- For a stable address, use Tailscale Funnel (below), or a free Cloudflare account with a domain and a named tunnel instead of `--url`.
- Anyone with the address can register. Keep `APP_JOBS_TANITJOBS_ENABLED=false`, and tell people that uploaded CVs are personal data stored on your machine (encrypted, key in `.env`).
- Back up the `dbdata` volume and the `APP_ENCRYPTION_KEY`; without the key stored CVs cannot be read.

## Other free hosts

Free tiers of Render, Koyeb and Fly.io are too small for Spring Boot plus Tika, need a card, or sleep when idle. Oracle Cloud Always Free fits the whole stack but asks for a card at sign-up.
