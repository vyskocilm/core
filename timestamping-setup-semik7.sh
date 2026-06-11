#!/bin/sh
./httpRequests/timestamping-setup.sh \
    --ilm-host https://semik7.3key.company \
    --auth-mode mtls \
    --client-p12-bundle /Users/i.raisr/Documents/GitHub/users/admin-default_00000000.p12 \
    --client-p12-password 00000000 \
    --insecure-tls \
    --time-quality-ntp-servers chronyd.time.svc.cluster.local \
    --pkcs12-bundle /Users/i.raisr/Documents/ISS/access/ejbca.3key.company\ -\ Ivo\ Raisr\ -\ 00000000.p12.p12 \
    --certificate-dn "hej-mistre-vstan-bystre-$(date +%Y%m%d-%H%M%S)"
