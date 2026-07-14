# Contabo deployment

This package runs FlexVertex, the Java scorer, and Caddy on one VPS. Only Caddy
publishes ports `80` and `443`; FlexVertex (`10000`, `8080`) and the scorer
(`8091`) stay on the Docker network.

The public architecture is:

```text
https://ttacs.co.ke/mpesa-scorer/ → Vercel rewrite → https://scorer.ttacs.co.ke/
                                                     → Caddy → scorer:8091
                                                               ↘ flexvertex:10000
```

The scorer intentionally stays mounted at `/`. Vercel strips the
`/mpesa-scorer` prefix, and the static UI uses relative asset, API, health, and
sample URLs so the browser preserves the public prefix.

## First deployment

1. Create a DNS `A` record for `scorer.ttacs.co.ke` pointing to the Contabo
   public IPv4 address. Wait for it to resolve before starting Caddy; Caddy uses
   it to obtain a Let's Encrypt certificate.
2. SSH to Ubuntu and install Docker Engine plus the Compose plugin, then log out
   and back in so your account can run Docker:

   ```bash
   sudo apt update
   sudo apt install -y ca-certificates curl git
   curl -fsSL https://get.docker.com | sudo sh
   sudo usermod -aG docker "$USER"
   exit
   ```

3. Clone the scorer and create the private deployment environment file:

   ```bash
   git clone https://github.com/billmalea/mpesa-credit-scorer.git
   cd mpesa-credit-scorer/deploy/contabo
   cp .env.example .env
   chmod 600 .env
   nano .env
   ```

   Set long, unique values for `FLEXVERTEX_PASSWORD` and `SCORER_BASIC_AUTH`;
   never commit or share `.env`.
4. Start Iron once, then copy its local client JARs into `lib/`. The FlexVertex
   JARs are deliberately not versioned, so this is required before the scorer
   image can be built:

   ```bash
   docker compose up -d flexvertex
   cd ../..
   ./scripts/sync-flexvertex-libs.sh
   cd deploy/contabo
   docker compose up --build -d
   ```

5. Confirm private services and the HTTPS endpoint:

   ```bash
   docker compose ps
   docker compose logs -f scorer
   curl -I https://scorer.ttacs.co.ke/health
   ```

## Firewall

Allow SSH before enabling UFW, then publish only HTTP(S):

```bash
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw deny 8091/tcp
sudo ufw deny 10000/tcp
sudo ufw deny 8080/tcp
sudo ufw enable
sudo ufw status numbered
```

Do not add Docker `ports:` entries for the scorer or FlexVertex. Docker-published
ports can bypass UFW rules, which is why this compose file uses `expose:` for
them.

## Vercel (new-ttacs / ttacs.co.ke)

The site repo is https://github.com/billmalea/new-ttacs.git (Vite + React).

Branch `feat/mpesa-scorer-proxy` updates `vercel.json` so `/mpesa-scorer`
is proxied to Contabo **before** the SPA catch-all:

```text
/mpesa-scorer  → redirect → /mpesa-scorer/
/mpesa-scorer/ → https://scorer.ttacs.co.ke/
/mpesa-scorer/:path* → https://scorer.ttacs.co.ke/:path*
/(.*) → /index.html
```

Open a PR from:
https://github.com/billmalea/new-ttacs/pull/new/feat/mpesa-scorer-proxy

After merge + Vercel deploy, and after Contabo is healthy:

```bash
curl -I https://ttacs.co.ke/mpesa-scorer/
```

## What you still need on Contabo

1. Contabo **public IPv4**
2. DNS `A` record: `scorer.ttacs.co.ke` → that IP (at your `.co.ke` DNS host)
3. SSH in, follow the steps above
4. Merge the Vercel PR and wait for production deploy


## Operations

```bash
docker compose pull
docker compose up --build -d
docker compose logs -f --tail=100 scorer
docker compose down                 # keeps named FlexVertex/Caddy volumes
```

Back up the `flexvertex-data` named volume before upgrades. Caddy certificates
and state live in the `caddy-data` volume.
