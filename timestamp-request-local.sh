#!/bin/sh
$HOST=http://localhost:8080
curl -v -X POST -H 'Content-Type: application/timestamp-query' --data-binary @request.tsq "$HOST/api/v1/protocols/tsp/tsp-non-qualified/sign" -o response-non-qualified-local.tsr
curl -v -X POST -H 'Content-Type: application/timestamp-query' --data-binary @request.tsq "$HOST/api/v1/protocols/tsp/tsp-qualified/signi" -o response-qualified-local.tsr
