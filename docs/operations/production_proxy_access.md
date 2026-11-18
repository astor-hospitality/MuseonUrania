# VEDAL production proxy access

The shared Caddyfile keeps `/admin` and `/api/admin` restricted to private
networks by default. To retain the currently approved public access on the
VEDAL production host only, set `VEDAL_ADMIN_ALLOW="0.0.0.0/0 ::/0"` in the
host's protected `backend/.env`. Compose passes this setting to the proxy;
changing the file alone does not update a running container. Recreate only the
proxy after validating the full configuration, and verify the public site,
login page, and that the admin API returns 401 without authentication.

This setting removes the network access layer for this host, not the need for
authentication and role checks. Mandatory MFA for every privileged user must
be verified separately; public exposure is not proof that MFA is active.
To restore network restriction, set `VEDAL_ADMIN_ALLOW=private_ranges` and
recreate the proxy. Plan for a brief interruption across all sites using it.
Keep `.env`, credentials, and host-specific secrets out of Git.
