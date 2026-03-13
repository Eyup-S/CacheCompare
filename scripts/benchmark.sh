#!/usr/bin/env bash
# Cache Comparison Benchmark Script
# Usage: ./benchmark.sh [host] [count] [label]
# Example: ./benchmark.sh localhost:8080 1000 redis-lazy

HOST="${1:-localhost:8080}"
COUNT="${2:-1000}"
LABEL="${3:-unknown}"
RESULTS_DIR="benchmark-results"

mkdir -p "$RESULTS_DIR"
OUTFILE="$RESULTS_DIR/${LABEL}-$(date +%Y%m%d-%H%M%S).csv"

echo "timestamp_ms,product_id,http_status,duration_ms" > "$OUTFILE"
echo "Starting benchmark: $COUNT requests against http://$HOST (label=$LABEL)"
echo ""

SUCCESS=0
FAIL=0
TOTAL_MS=0

for i in $(seq 1 "$COUNT"); do
    ID=$(( (RANDOM % 1000) + 1 ))

    START_NS=$(date +%s%N)
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        --max-time 10 \
        "http://$HOST/products/$ID")
    END_NS=$(date +%s%N)

    DURATION_MS=$(( (END_NS - START_NS) / 1000000 ))
    TIMESTAMP_MS=$(date +%s%3N)

    echo "$TIMESTAMP_MS,$ID,$STATUS,$DURATION_MS" >> "$OUTFILE"

    if [ "$STATUS" = "200" ]; then
        SUCCESS=$(( SUCCESS + 1 ))
        TOTAL_MS=$(( TOTAL_MS + DURATION_MS ))
    else
        FAIL=$(( FAIL + 1 ))
    fi

    # Progress indicator every 100 requests
    if [ $(( i % 100 )) -eq 0 ]; then
        echo "  [$i/$COUNT] done..."
    fi
done

if [ "$SUCCESS" -gt 0 ]; then
    AVG_MS=$(( TOTAL_MS / SUCCESS ))
else
    AVG_MS=0
fi

echo ""
echo "=== Results: $LABEL ==="
echo "Total requests : $COUNT"
echo "Success (200)  : $SUCCESS"
echo "Failures       : $FAIL"
echo "Avg duration   : ${AVG_MS}ms (HTTP round-trip, successful requests only)"
echo "Results CSV    : $OUTFILE"
echo ""
echo "To analyze server-side cache timings:"
echo "  cat logs/cache-benchmark.jsonl | jq 'select(.result != \"WARM_UP\") | .durationMs' | awk '{s+=\$1;n++} END {print \"avg:\", s/n, \"ms\"}'"
