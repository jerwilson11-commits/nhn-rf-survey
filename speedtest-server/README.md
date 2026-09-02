# NHN throughput endpoint

A small download/upload responder for the survey app to measure against, so throughput testing does
not depend on a public speed-test host.

## Why

Public endpoints refuse sustained automated use. On the 2026-09-02 walk, eight consecutive download
bursts against a public host returned **HTTP 429** and every download in the session was lost. The
uploads happened to be spaced differently and survived, so the dataset came back with eight
upload-only rows and nothing to explain the gap — which in a client report reads as *"not measured
here"* rather than *"the endpoint refused us"*.

Rate limiting is also not a property of the network under test. A survey that reports it as one is
reporting a fault at the wrong address.

Owning the endpoint removes the refusals, gives both directions, and lets a report state the
measurement path precisely: *"measured against NHN's endpoint at `speed.example.com`, Ashburn VA"*
rather than *"a public speed test, when it allowed us"*.

## What it is

Streams pre-generated random bytes from memory, discards uploads without touching disk. Python
standard library only — no dependencies to install or keep patched.

Random rather than zeroes, deliberately: a compressing middlebox between the handset and the server
would squash a block of zeroes and report a throughput the link cannot deliver.

| Route | Method | Purpose |
|---|---|---|
| `/down?bytes=N` | GET | Stream N random bytes, capped at 200 MB |
| `/up` | POST | Read and discard, reply with the byte count |
| `/ping` | GET | One-byte reply, for latency |
| `/health` | GET | Readiness check |

## Verified locally, 2026-09-02

- 10 MB download served at ~3.5 Gbps — the server is nowhere near the bottleneck for a handset
- **Ten consecutive 25 MB requests: all HTTP 200**, the exact pattern that earned a 429 in the field
- Chunked upload accepted (3 MB), which is what the app sends — it cannot know in advance how many
  bytes fit inside its burst window
- Client aborting mid-download is handled without a traceback, which every burst does by design
- 200 MB cap enforced against a 500 MB request

## Deploying

Any VPS with a public IP. The smallest tier at Hetzner, DigitalOcean, Vultr or Linode is ample —
the workload is streaming bytes, not computing. **Pick a region near where you survey**, since
latency to the endpoint is part of what you measure.

### 1. The service

```bash
sudo mkdir -p /opt/nhn-speedtest
sudo cp server.py /opt/nhn-speedtest/
sudo useradd --system --no-create-home --shell /usr/sbin/nologin nhnspeed
```

`/etc/systemd/system/nhn-speedtest.service`:

```ini
[Unit]
Description=NHN throughput endpoint
After=network.target

[Service]
Type=simple
User=nhnspeed
ExecStart=/usr/bin/python3 /opt/nhn-speedtest/server.py --host 127.0.0.1 --port 8080
Restart=always
RestartSec=2
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now nhn-speedtest
curl -s localhost:8080/health   # expect: ok
```

It binds **loopback**. TLS termination is the reverse proxy's job.

### 2. TLS, and why it is not optional

**Android blocks cleartext HTTP by default**, and the app does not carry an exception for it. An
`http://` endpoint will simply fail to connect. You need a domain and a certificate.

Caddy is the least work, because it obtains and renews the certificate itself:

```bash
sudo apt install caddy
```

`/etc/caddy/Caddyfile`:

```
speed.yourdomain.com {
    reverse_proxy 127.0.0.1:8080 {
        flush_interval -1
    }
}
```

`flush_interval -1` matters: without it Caddy buffers the response and you measure the proxy's
buffering rather than the link.

```bash
sudo systemctl reload caddy
curl -s https://speed.yourdomain.com/health   # expect: ok
```

Point a DNS A record at the VPS first, or the certificate request fails.

### 3. Firewall

```bash
sudo ufw allow 80,443/tcp
sudo ufw enable
```

Port 8080 stays closed — only Caddy reaches it, over loopback.

### 4. Point the app at it

In the app's **Throughput** card, set the server base URL to:

```
https://speed.yourdomain.com/down?bytes=
```

The app appends the byte count. Upload and ping are derived from the same host automatically. The
walk bursts and the one-off speed test both read this field.

## Cost and exposure

Expect a few dollars a month. The real variable is **transfer**, not compute: a five-minute walk at
one burst per 40 seconds moves a few hundred megabytes, so a survey day is single-digit gigabytes.
Most hosts bundle a terabyte or more. Check whether yours bills overage before running a long
campaign.

There is no authentication, deliberately — a token in a survey tool that gets shared with
subcontractors is a token that leaks, and the thing being protected is bandwidth rather than data.
Nothing here touches persistent state and both directions carry random bytes. If it is discovered
and abused, the symptom is a transfer bill, so watch the graph for the first month. Lower
`MAX_BYTES` if you want a tighter ceiling.

## Running it locally to test

```bash
python3 server.py --port 8099 --verbose
```

Then `curl -o /dev/null -w '%{speed_download}\n' 'http://127.0.0.1:8099/down?bytes=25000000'`.
