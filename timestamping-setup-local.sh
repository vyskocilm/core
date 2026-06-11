#!/bin/sh
./httpRequests/timestamping-setup.sh \
    --client-cert-pem /Users/i.raisr/Documents/GitHub/users/admin.cert.pem \
    --pkcs12-bundle /Users/i.raisr/Documents/ISS/access/ejbca.3key.company\ -\ Ivo\ Raisr\ -\ 00000000.p12.p12 \
    --certificate-dn "hej-mistre-vstan-bystre-$(date +%Y%m%d-%H%M%S)"
