#!/bin/sh
HOST=https://semik7.3key.company
curl -v -X POST -H 'Content-Type: application/timestamp-query' --data-binary @request.tsq "$HOST/api/v1/protocols/tsp/tsp-non-qualified/sign" -o response-non-qualified-semik7.tsr
curl -v -X POST -H 'Content-Type: application/timestamp-query' --data-binary @request.tsq "$HOST/api/v1/protocols/tsp/tsp-qualified/sign" -o response-qualified-semik7.tsr
