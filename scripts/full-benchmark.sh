#!/usr/bin/env bash
# full-benchmark.sh - Tüm cache/strateji kombinasyonlarında benchmark çalıştırır
# Kullanım: ./scripts/full-benchmark.sh [internal|external]
#   internal → uygulamanın /products/benchmark/bulk endpoint'ini kullanır (network overhead'siz)
#   external → 1000 ayrı curl isteği atar (gerçek HTTP ölçümü)

set -euo pipefail

MODE="${1:-internal}"
if [[ "$MODE" != "internal" && "$MODE" != "external" ]]; then
    echo "Hata: Geçersiz mod '$MODE'. Kullanım: $0 [internal|external]"
    exit 1
fi

# ── Ayarlar ────────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
APP_DIR="$PROJECT_ROOT/cachecompare-web"
JAR_PATH="$APP_DIR/target/cachecompare-0.0.1-SNAPSHOT.jar"
APP_PORT=8080
BASE_URL="http://localhost:$APP_PORT"
BULK_COUNT=1000
RESULTS_DIR="$PROJECT_ROOT/benchmark-results"
LOGS_DIR="$PROJECT_ROOT/logs"

PROFILES=("redis" "hazelcast" "inmemory")
STRATEGIES=("lazy" "eager")

mkdir -p "$RESULTS_DIR" "$LOGS_DIR"

# ── Yardımcı fonksiyonlar ───────────────────────────────────────────────────────
log() { echo "[$(date '+%H:%M:%S')] $*"; }

# macOS uyumlu milisaniye timestamp
now_ms() { python3 -c "import time; print(int(time.time() * 1000))"; }

# Portu dinleyen süreci öldür
kill_port() {
    local pid
    pid=$(lsof -ti tcp:"$APP_PORT" 2>/dev/null || true)
    if [ -n "$pid" ]; then
        log "Port $APP_PORT'u kullanan süreç (PID=$pid) durduruluyor..."
        kill "$pid" 2>/dev/null || true
        sleep 2
    fi
}

wait_for_app() {
    local max_attempts=90
    local attempt=0
    log "Uygulama başlaması bekleniyor (PID=$APP_PID)..."
    while true; do
        # Önce sürecin hâlâ çalışıp çalışmadığını kontrol et
        if ! kill -0 "$APP_PID" 2>/dev/null; then
            log "HATA: Süreç (PID=$APP_PID) beklenmedik şekilde sonlandı."
            return 1
        fi

        if curl -sf --max-time 2 "$BASE_URL/products/1" > /dev/null 2>&1; then
            log "Uygulama hazır (${attempt}s)."
            return 0
        fi

        attempt=$(( attempt + 1 ))
        if [ "$attempt" -ge "$max_attempts" ]; then
            log "HATA: Uygulama $max_attempts saniye içinde başlamadı!"
            return 1
        fi
        sleep 1
    done
}

stop_app() {
    if [ -n "${APP_PID:-}" ] && kill -0 "$APP_PID" 2>/dev/null; then
        log "Uygulama durduruluyor (PID=$APP_PID)..."
        kill "$APP_PID"
        wait "$APP_PID" 2>/dev/null || true
        APP_PID=""
        sleep 2
    fi
}

run_internal_benchmark() {
    local label="$1"
    log "  → İç benchmark başlıyor ($BULK_COUNT istek)..."
    local result
    result=$(curl -sf --max-time 60 "$BASE_URL/products/benchmark/bulk?count=$BULK_COUNT")
    echo "$result" > "$RESULTS_DIR/${label}-internal.json"
    python3 -c "
import json, sys
d = json.loads('$result'.replace(\"'\", \"'\"))
print(f\"    count={d['count']} hits={d['hits']} misses={d['misses']} totalWallMs={d['totalWallMs']} avgMs={d['avgMs']:.4f} minMs={d['minMs']:.4f} maxMs={d['maxMs']:.4f}\")
" 2>/dev/null || echo "    Ham sonuç: $result"
}

run_external_benchmark() {
    local label="$1"
    log "  → Dış benchmark başlıyor ($BULK_COUNT istek)..."
    bash "$SCRIPT_DIR/benchmark.sh" "localhost:$APP_PORT" "$BULK_COUNT" "$label"
}

print_cache_stats() {
    local label="$1"
    local jsonl_file="$RESULTS_DIR/${label}-cache.jsonl"
    if [ ! -f "$jsonl_file" ]; then
        echo "veri yok"
        return
    fi
    python3 - "$jsonl_file" <<'PYEOF'
import json, sys
entries = []
with open(sys.argv[1]) as f:
    for line in f:
        line = line.strip()
        if line:
            d = json.loads(line)
            if d.get('result') != 'WARM_UP':
                entries.append(d['durationMs'])
if entries:
    avg = sum(entries) / len(entries)
    print(f"n={len(entries)} avg={avg:.4f}ms min={min(entries):.4f}ms max={max(entries):.4f}ms")
else:
    print("veri yok (yalnızca WARM_UP kayıtları)")
PYEOF
}

# ── Build ───────────────────────────────────────────────────────────────────────
if [ ! -f "$JAR_PATH" ]; then
    log "JAR bulunamadı, build başlıyor..."
    cd "$APP_DIR" && ./mvnw package -q -DskipTests
    cd "$PROJECT_ROOT"
    log "Build tamamlandı."
else
    log "JAR mevcut, build atlanıyor."
fi

# ── Ana döngü ───────────────────────────────────────────────────────────────────
SUMMARY_FILE="$RESULTS_DIR/summary-$(date +%Y%m%d-%H%M%S).txt"
echo "Cache Comparison Benchmark — $(date)" > "$SUMMARY_FILE"
echo "Mode: $MODE | Count: $BULK_COUNT" >> "$SUMMARY_FILE"
echo "────────────────────────────────────────" >> "$SUMMARY_FILE"

APP_PID=""
trap stop_app EXIT

for PROFILE in "${PROFILES[@]}"; do
    for STRATEGY in "${STRATEGIES[@]}"; do
        LABEL="${PROFILE}-${STRATEGY}"
        echo ""
        log "════════════════════════════════════════"
        log "Kombinasyon: $LABEL"
        log "════════════════════════════════════════"

        # Portta çalışan varsa önce öldür
        kill_port

        # Lazy testlerinden önce cache'i temizle (kirlenmiş veri olmasın)
        if [ "$STRATEGY" = "lazy" ]; then
            case "$PROFILE" in
                redis)
                    log "Redis FLUSHALL yapılıyor (temiz ölçüm için)..."
                    docker exec cachecompare-redis redis-cli FLUSHALL > /dev/null 2>&1 || \
                        log "UYARI: Redis FLUSHALL başarısız"
                    ;;
                hazelcast)
                    log "Hazelcast container yeniden başlatılıyor (temiz ölçüm için)..."
                    docker restart cachecompare-hazelcast > /dev/null 2>&1 || \
                        log "UYARI: Hazelcast restart başarısız"
                    log "Hazelcast hazır olması bekleniyor..."
                    for i in $(seq 1 30); do
                        if curl -sf http://localhost:5701/hazelcast/health/ready > /dev/null 2>&1; then
                            log "Hazelcast hazır (${i}s)."; break
                        fi
                        sleep 1
                    done
                    ;;
            esac
        fi

        # Önceki log dosyasını temizle
        rm -f "$LOGS_DIR/cache-benchmark.jsonl"

        # Uygulamayı başlat
        log "Uygulama başlatılıyor (profile=$PROFILE, strategy=$STRATEGY)..."
        java -jar "$JAR_PATH" \
            --spring.profiles.active="$PROFILE" \
            --cache.strategy="$STRATEGY" \
            --server.port="$APP_PORT" \
            --cache.log.dir="$LOGS_DIR" \
            > "$RESULTS_DIR/${LABEL}-app.log" 2>&1 &
        APP_PID=$!

        if ! wait_for_app; then
            log "HATA: $LABEL kombinasyonu atlanıyor. Detay: $RESULTS_DIR/${LABEL}-app.log"
            stop_app
            echo "$LABEL → BAŞARISIZ (uygulama başlamadı)" >> "$SUMMARY_FILE"
            continue
        fi

        # Benchmark çalıştır
        START_MS=$(now_ms)
        if [ "$MODE" = "internal" ]; then
            run_internal_benchmark "$LABEL"
        else
            run_external_benchmark "$LABEL"
        fi
        END_MS=$(now_ms)
        WALL_MS=$(( END_MS - START_MS ))

        # Cache loglarını kaydet
        if [ -f "$LOGS_DIR/cache-benchmark.jsonl" ]; then
            cp "$LOGS_DIR/cache-benchmark.jsonl" "$RESULTS_DIR/${LABEL}-cache.jsonl"
            LINE_COUNT=$(wc -l < "$RESULTS_DIR/${LABEL}-cache.jsonl" | tr -d ' ')
            log "  → Cache log kaydedildi: ${LABEL}-cache.jsonl ($LINE_COUNT satır)"
            STATS=$(print_cache_stats "$LABEL")
            log "  → Cache süreleri (WARM_UP hariç): $STATS"
            echo "$LABEL → $STATS (wall=${WALL_MS}ms)" >> "$SUMMARY_FILE"
        else
            log "  → Uyarı: Cache log dosyası bulunamadı."
            echo "$LABEL → log yok (wall=${WALL_MS}ms)" >> "$SUMMARY_FILE"
        fi

        # Uygulamayı durdur
        stop_app
    done
done

echo ""
log "════════════════════════════════════════"
log "Tüm kombinasyonlar tamamlandı!"
log "Sonuçlar: $RESULTS_DIR/"
log "Özet: $SUMMARY_FILE"
log "════════════════════════════════════════"
echo ""
cat "$SUMMARY_FILE"
