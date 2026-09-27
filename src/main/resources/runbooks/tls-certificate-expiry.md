# Expired TLS certificate

## Symptoms
- `SSLHandshakeException`, `PKIX path validation failed`, `certificate expired`
- Sudden 502 errors when calling an upstream over HTTPS
- Login or token endpoints failing for all users at once

## Diagnosis
1. Check the certificate: `openssl s_client -connect host:443 | openssl x509 -noout -dates`.
2. Confirm whether cert-manager or the renewal job failed.

## Fix
1. Renew or re-issue the certificate (cert-manager: delete the failed CertificateRequest to retry).
2. Roll out the new secret and restart the pods that cache it.
3. Verify the handshake works, then watch the login success rate.
4. Add expiry alerts at 30, 14 and 3 days.
