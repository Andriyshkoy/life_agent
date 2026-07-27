# Life Agent nginx bootstrap

This directory contains the dedicated bootstrap vhost for
`life.andriyshkoy.ru`.

The bootstrap exposes only:

```text
GET or HEAD /healthz
```

Other methods are rejected and every other path returns `404`. API proxy routes
are intentionally absent until the authenticated sync service exists.

The installer:

1. refuses to change nginx before the domain resolves;
2. takes an exclusive host-level lock and refuses directory-shaped targets;
3. refuses to overwrite an existing exact-name vhost;
4. reuses an existing certificate lineage only when its complete SAN set is
   exactly `life.andriyshkoy.ru`, which makes a post-issuance retry safe without
   accepting a shared certificate;
5. enables a temporary HTTP-only vhost;
6. obtains the dedicated Let's Encrypt certificate, or keeps the matching
   lineage until it is due for renewal;
7. installs the final HTTPS vhost;
8. validates and reloads nginx at every transition;
9. rolls the vhost back and exits non-zero if a step or signal interrupts it.

Run on the server from this directory:

```bash
sudo ./install-life-vhost.sh
```

The exact configuration files of existing sites are not edited. Certbot and the
installer do validate and reload the shared system nginx, so every failure path
restores the exact-name Life Agent vhost and validates the resulting global
configuration. A usable default Certbot account is checked before nginx is
changed; this server already has one. The final built-in probe waits for the
reloaded SNI vhost and validates TLS on the host; public reachability must also
be checked from another network.
The server account intentionally requires an interactive sudo authorization
for this one host-level operation.
