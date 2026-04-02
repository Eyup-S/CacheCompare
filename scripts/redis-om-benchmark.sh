#!/usr/bin/env bash
# redis-om-benchmark.sh
# RedisTemplate vs Redis OM for Spring karşılaştırmalı benchmark
#
# Kullanım: ./scripts/redis-om-benchmark.sh [internal|external]
#   internal → /products/benchmark/bulk endpoint (network overhead'siz, önerilen)
#   external → ayrı curl istekleri (gerçek HTTP ölçümü)
#
# Kombinasyonlar (8 adet):
#   {redis-template, redis-om} × {lazy, eager} × {1k, 200k}
#
# Gereksinimler:
#   - cachecompare-redis-stack container (docker-compose up redis-stack)
#   - cachecompare-postgres container (docker-compose up postgres)
#   - JAR: cachecompare-redis-om/target/cachecompare-redis-om-0.0.1-SNAPSHOT.jar

set -euo pipefail

MODE="${1:-internal}"
if [[ "$MODE" != "internal" && "$MODE" != "external" ]]; then
    echo "Hata: Geçersiz mod '$MODE'. Kullanım: $0 [internal|external]"
    exit 1
fi

# ── Ayarlar ──────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
APP_DIR="$PROJECT_ROOT/cachecompare-redis-om"
JAR_PATH="$APP_DIR/target/cachecompare-redis-om-0.0.1-SNAPSHOT.jar"
APP_PORT=8081                    # 8080'den farklı port (mevcut modülle çakışmasın)
BASE_URL="http://localhost:$APP_PORT"
BULK_COUNT=1000                  # Her kombinasyon için istek sayısı
RESULTS_DIR="$PROJECT_ROOT/benchmark-results-redis-om"
LOGS_DIR="$PROJECT_ROOT/logs-redis-om"
REDIS_STACK_HOST="localhost"
REDIS_STACK_PORT=6380

PROVIDERS=("redis-template" "redis-om")
STRATEGIES=("lazy" "eager")
DATASET_SIZES=("1000" "200000")  # 1k ve 200k

mkdir -p "$RESULTS_DIR" "$LOGS_DIR"

# ── Yardımcı fonksiyonlar ────────────────────────────────────────────────────
log() { echo "[$(date '+%H:%M:%S')] $*"; }
now_ms() { python3 -c "import time; print(int(time.time() * 1000))"; }
size_label() { [[ "$1" == "200000" ]] && echo "200k" || echo "1k"; }

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
    # Büyük dataset eager ısınması için daha uzun timeout
    local max_attempts="${1:-120}"
    local attempt=0
    log "Uygulama başlaması bekleniyor (PID=$APP_PID, max=${max_attempts}s)..."
    while true; do
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

wait_for_warmup() {
    local strategy="$1"
    local max_wait="${2:-600}"   # saniye cinsinden maksimum bekleme

    # Lazy stratejide warm-up yoktur
    if [ "$strategy" = "lazy" ]; then
        return 0
    fi

    log "Eager warm-up tamamlanması bekleniyor (maks ${max_wait}s)..."
    local elapsed=0
    while true; do
        local response
        response=$(curl -sf --max-time 5 "$BASE_URL/warmup/status" 2>/dev/null || echo '{}')

        local ready loaded total pct
        ready=$(echo "$response" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('ready', False))" 2>/dev/null || echo "False")
        loaded=$(echo "$response" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('loaded', 0))" 2>/dev/null || echo "0")
        total=$(echo "$response"  | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('total', 0))"  2>/dev/null || echo "0")
        pct=$(echo "$response"    | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('progressPct', 0))" 2>/dev/null || echo "0")

        if [ "$ready" = "True" ]; then
            log "  → Warm-up tamamlandı: $loaded/$total kayıt cache'e yüklendi."
            return 0
        fi

        if [ "$elapsed" -ge "$max_wait" ]; then
            log "HATA: Warm-up $max_wait saniye içinde tamamlanamadı! ($loaded/$total)"
            return 1
        fi

        if [ $(( elapsed % 30 )) -eq 0 ] && [ "$elapsed" -gt 0 ]; then
            log "  → Warm-up devam ediyor: $loaded/$total (%${pct}) — ${elapsed}s geçti"
        fi

        sleep 2
        elapsed=$(( elapsed + 2 ))
    done
}

flush_redis_stack() {
    log "Redis Stack FLUSHALL yapılıyor (temiz ölçüm için)..."
    docker exec cachecompare-redis-stack redis-cli FLUSHALL > /dev/null 2>&1 || \
        log "UYARI: Redis Stack FLUSHALL başarısız"
}

seed_postgres() {
    local target_count="$1"
    log "PostgreSQL seed kontrol ediliyor (hedef: $target_count)..."
    local result
    result=$(curl -sf --max-time 600 -X POST \
        "$BASE_URL/seed?count=$target_count" 2>/dev/null || echo '{"inserted":-1}')
    local inserted
    inserted=$(echo "$result" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('inserted',-1))" 2>/dev/null || echo "-1")

    if [ "$inserted" = "-1" ]; then
        log "UYARI: Seed endpoint'e ulaşılamadı veya hata döndü. Devam ediliyor..."
    elif [ "$inserted" = "0" ]; then
        log "  → Seed: zaten $target_count kayıt mevcut."
    else
        log "  → Seed: $inserted kayıt eklendi (toplam: $target_count)."
    fi
}

run_internal_benchmark() {
    local label="$1"
    log "  → İç benchmark başlıyor ($BULK_COUNT istek)..."
    local result
    result=$(curl -sf --max-time 120 \
        "$BASE_URL/products/benchmark/bulk?count=$BULK_COUNT" 2>/dev/null || echo '{}')
    echo "$result" > "$RESULTS_DIR/${label}-internal.json"
    python3 -c "
import json, sys
try:
    d = json.loads('''$result''')
    print(f\"    count={d.get('count','?')} hits={d.get('hits','?')} misses={d.get('misses','?')} totalWallMs={d.get('totalWallMs','?')} avgMs={float(d.get('avgMs',0)):.4f} minMs={float(d.get('minMs',0)):.4f} maxMs={float(d.get('maxMs',0)):.4f}\")
except Exception as e:
    print(f'    Ham sonuç: $result')
" 2>/dev/null || echo "    Ham sonuç: $result"
}

run_external_benchmark() {
    local label="$1"
    local dataset_size="$2"
    log "  → Dış benchmark başlıyor ($BULK_COUNT istek, dataset=$dataset_size)..."

    local outfile="$RESULTS_DIR/${label}-external.csv"
    echo "timestamp_ms,product_id,http_status,duration_ms" > "$outfile"

    local success=0 fail=0 total_ms=0

    for i in $(seq 1 "$BULK_COUNT"); do
        local id=$(( (RANDOM % dataset_size) + 1 ))
        local start_ns end_ns duration_ms status ts_ms

        start_ns=$(date +%s%N)
        status=$(curl -s -o /dev/null -w "%{http_code}" \
            --max-time 10 "http://localhost:$APP_PORT/products/$id")
        end_ns=$(date +%s%N)

        duration_ms=$(( (end_ns - start_ns) / 1000000 ))
        ts_ms=$(date +%s%3N)
        echo "$ts_ms,$id,$status,$duration_ms" >> "$outfile"

        if [ "$status" = "200" ]; then
            success=$(( success + 1 ))
            total_ms=$(( total_ms + duration_ms ))
        else
            fail=$(( fail + 1 ))
        fi

        if [ $(( i % 200 )) -eq 0 ]; then
            log "    [$i/$BULK_COUNT] tamamlandı..."
        fi
    done

    local avg_ms=0
    [ "$success" -gt 0 ] && avg_ms=$(( total_ms / success ))
    log "    Sonuç: success=$success fail=$fail avg_ms=${avg_ms}ms"
}

print_cache_stats() {
    local jsonl_file="$1"
    [ ! -f "$jsonl_file" ] && echo "veri yok" && return
    python3 - "$jsonl_file" <<'PYEOF'
import json, sys
hits, misses, durations = [], [], []
with open(sys.argv[1]) as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        d = json.loads(line)
        if d.get('result') == 'WARM_UP':
            continue
        durations.append(d['durationMs'])
        if d.get('result') == 'HIT':
            hits.append(d['durationMs'])
        else:
            misses.append(d['durationMs'])
if durations:
    avg = sum(durations) / len(durations)
    hit_rate = len(hits) / len(durations) * 100
    print(f"n={len(durations)} avg={avg:.4f}ms min={min(durations):.4f}ms max={max(durations):.4f}ms hit_rate={hit_rate:.1f}%")
else:
    print("veri yok")
PYEOF
}

# ── Build ────────────────────────────────────────────────────────────────────
if [ ! -f "$JAR_PATH" ]; then
    log "JAR bulunamadı, build başlıyor..."
    cd "$APP_DIR" && ./mvnw package -q -DskipTests
    cd "$PROJECT_ROOT"
    log "Build tamamlandı."
else
    log "JAR mevcut, build atlanıyor. Yeniden build için JAR'ı silin."
fi

# ── Redis Stack sağlık kontrolü ──────────────────────────────────────────────
log "Redis Stack kontrol ediliyor (docker: cachecompare-redis-stack)..."
if ! docker exec cachecompare-redis-stack redis-cli ping > /dev/null 2>&1; then
    log "HATA: Redis Stack erişilemiyor. 'docker-compose up redis-stack' çalıştırın."
    exit 1
fi
log "Redis Stack hazır."

# ── Özet dosyası ─────────────────────────────────────────────────────────────
SUMMARY_FILE="$RESULTS_DIR/summary-$(date +%Y%m%d-%H%M%S).txt"
{
    echo "RedisTemplate vs Redis OM for Spring Benchmark"
    echo "Tarih: $(date)"
    echo "Mod: $MODE | İstek sayısı: $BULK_COUNT"
    echo "────────────────────────────────────────────────"
    printf "%-40s %-10s %-10s %-10s %-10s %s\n" \
        "Kombinasyon" "n" "avg_ms" "min_ms" "max_ms" "hit_rate"
    echo "────────────────────────────────────────────────"
} > "$SUMMARY_FILE"

APP_PID=""
trap stop_app EXIT

# ── Ana döngü ────────────────────────────────────────────────────────────────
for DATASET_SIZE in "${DATASET_SIZES[@]}"; do
    SIZE_LBL=$(size_label "$DATASET_SIZE")
    # 200k eager'da cache ısınması uzun sürer; daha geniş timeout
    WAIT_TIMEOUT=120
    [[ "$DATASET_SIZE" == "200000" ]] && WAIT_TIMEOUT=600

    log ""
    log "════════════════════════════════════════════════"
    log "DATASET: $SIZE_LBL ($DATASET_SIZE kayıt)"
    log "════════════════════════════════════════════════"

    # Dataset için önce bir kez seed (veri kalıcı; ikinci çalıştırmada atlanır)
    # Seed için geçici olarak redis-template profiliyle uygulamayı başlatıyoruz
    kill_port
    flush_redis_stack
    rm -f "$LOGS_DIR/cache-benchmark.jsonl"

    log "Seed uygulaması başlatılıyor (redis-template, lazy, dataset=$DATASET_SIZE)..."
    java -jar "$JAR_PATH" \
        --spring.profiles.active="redis-template" \
        --cache.strategy="lazy" \
        --dataset.size="$DATASET_SIZE" \
        --server.port="$APP_PORT" \
        --cache.log.dir="$LOGS_DIR" \
        > "$RESULTS_DIR/seed-${SIZE_LBL}-app.log" 2>&1 &
    APP_PID=$!

    if ! wait_for_app 120; then
        log "HATA: Seed uygulaması başlamadı. Detay: $RESULTS_DIR/seed-${SIZE_LBL}-app.log"
        stop_app
        continue
    fi

    seed_postgres "$DATASET_SIZE"
    stop_app
    sleep 1

    for PROVIDER in "${PROVIDERS[@]}"; do
        for STRATEGY in "${STRATEGIES[@]}"; do
            LABEL="${PROVIDER}-${SIZE_LBL}-${STRATEGY}"
            echo ""
            log "────────────────────────────────────────"
            log "Kombinasyon: $LABEL"
            log "────────────────────────────────────────"

            kill_port
            flush_redis_stack
            rm -f "$LOGS_DIR/cache-benchmark.jsonl"

            log "Uygulama başlatılıyor (profile=$PROVIDER, strategy=$STRATEGY, dataset=$DATASET_SIZE)..."
            java -jar "$JAR_PATH" \
                --spring.profiles.active="$PROVIDER" \
                --cache.strategy="$STRATEGY" \
                --dataset.size="$DATASET_SIZE" \
                --server.port="$APP_PORT" \
                --cache.log.dir="$LOGS_DIR" \
                > "$RESULTS_DIR/${LABEL}-app.log" 2>&1 &
            APP_PID=$!

            if ! wait_for_app "$WAIT_TIMEOUT"; then
                log "HATA: $LABEL atlanıyor. Detay: $RESULTS_DIR/${LABEL}-app.log"
                stop_app
                echo "$LABEL → BAŞARISIZ (uygulama başlamadı)" >> "$SUMMARY_FILE"
                continue
            fi

            # Eager stratejide warm-up tamamlanana kadar bekle
            if ! wait_for_warmup "$STRATEGY" "$WAIT_TIMEOUT"; then
                log "HATA: $LABEL warm-up tamamlanamadı, atlanıyor."
                stop_app
                echo "$LABEL → BAŞARISIZ (warm-up timeout)" >> "$SUMMARY_FILE"
                continue
            fi

            START_MS=$(now_ms)
            if [ "$MODE" = "internal" ]; then
                run_internal_benchmark "$LABEL"
            else
                run_external_benchmark "$LABEL" "$DATASET_SIZE"
            fi
            END_MS=$(now_ms)
            WALL_MS=$(( END_MS - START_MS ))

            # Cache loglarını kaydet
            if [ -f "$LOGS_DIR/cache-benchmark.jsonl" ]; then
                cp "$LOGS_DIR/cache-benchmark.jsonl" \
                   "$RESULTS_DIR/${LABEL}-cache.jsonl"
                LINE_COUNT=$(wc -l < "$RESULTS_DIR/${LABEL}-cache.jsonl" | tr -d ' ')
                log "  → Cache log: ${LABEL}-cache.jsonl ($LINE_COUNT satır)"
                STATS=$(print_cache_stats "$RESULTS_DIR/${LABEL}-cache.jsonl")
                log "  → Sonuç (WARM_UP hariç): $STATS"
                echo "$LABEL | $STATS | wall=${WALL_MS}ms" >> "$SUMMARY_FILE"
            else
                log "  → UYARI: Cache log bulunamadı."
                echo "$LABEL → log yok (wall=${WALL_MS}ms)" >> "$SUMMARY_FILE"
            fi

            stop_app
        done
    done
done

echo ""
log "════════════════════════════════════════════════"
log "Tüm kombinasyonlar tamamlandı!"
log "════════════════════════════════════════════════"

# ── Alan Bazlı Arama Benchmark ───────────────────────────────────────────────
SEARCH_COUNT=500
SEARCH_DIR="$RESULTS_DIR/search"
mkdir -p "$SEARCH_DIR"

log ""
log "════════════════════════════════════════════════"
log "ALAN BAZLI ARAMA BENCHMARK"
log "  Sorgu sayısı: $SEARCH_COUNT"
log "  2-field: category + brand (exact match)"
log "  4-field: + price range + stock range"
log "════════════════════════════════════════════════"

run_search_scenario() {
    local provider="$1"   # redis-om | redis-template
    local dataset_size="$2"
    local size_lbl
    size_lbl=$(size_label "$dataset_size")
    local label="search-${provider}-${size_lbl}"

    log ""
    log "────────────────────────────────────────"
    log "Arama: provider=$provider dataset=$size_lbl"
    log "────────────────────────────────────────"

    kill_port
    flush_redis_stack

    # Her iki provider için de lazy başlatıp /search/load çağırıyoruz.
    # Böylece yalnızca dataset.size kadar kayıt arama store'a girer → adil karşılaştırma.
    # (redis-om eager ile başlatıldığında warm-up tüm 200k kaydı ProductDocument'e yazıyor
    #  ve 1k senaryosunda da aslında 200k üzerinde sorgulama yapılıyor olurdu.)
    local strategy="lazy"

    log "Uygulama başlatılıyor (profile=$provider, strategy=$strategy, dataset=$dataset_size)..."
    java -jar "$JAR_PATH" \
        --spring.profiles.active="$provider" \
        --cache.strategy="$strategy" \
        --dataset.size="$dataset_size" \
        --server.port="$APP_PORT" \
        --cache.log.dir="$LOGS_DIR" \
        > "$SEARCH_DIR/${label}-app.log" 2>&1 &
    APP_PID=$!

    local wait_timeout=120
    [[ "$dataset_size" == "200000" ]] && wait_timeout=600

    if ! wait_for_app "$wait_timeout"; then
        log "HATA: $label uygulaması başlamadı."
        stop_app
        return 1
    fi

    # Her iki provider da explicit /search/load ile sadece datasetSize kayıt yükler.
    if [[ "$provider" == "redis-om" ]]; then
        log "  → ProductDocument yükleniyor (RediSearch @Document index, dataset=$dataset_size)..."
        local load_result
        load_result=$(curl -sf --max-time 600 -X POST "$BASE_URL/search/load" 2>/dev/null || echo '{}')
        local loaded
        loaded=$(echo "$load_result" | python3 -c "import json,sys; print(json.load(sys.stdin).get('loaded',0))" 2>/dev/null || echo "?")
        log "  → $loaded kayıt ProductDocument store'a yüklendi."
    else
        log "  → ProductHash yükleniyor (ph: keyspace, @Indexed category+brand)..."
        local load_result
        load_result=$(curl -sf --max-time 600 -X POST "$BASE_URL/search/load" 2>/dev/null || echo '{}')
        local loaded
        loaded=$(echo "$load_result" | python3 -c "import json,sys; print(json.load(sys.stdin).get('loaded',0))" 2>/dev/null || echo "?")
        log "  → $loaded kayıt ProductHash store'a yüklendi."
    fi

    # 2-field benchmark
    log "  → 2-field sorgu benchmark ($SEARCH_COUNT sorgu)..."
    local result2
    result2=$(curl -sf --max-time 300 \
        "$BASE_URL/search/benchmark?type=2field&count=$SEARCH_COUNT" 2>/dev/null || echo '{}')
    echo "$result2" > "$SEARCH_DIR/${label}-2field.json"
    python3 -c "
import json, sys
try:
    d = json.loads('''$result2''')
    print(f\"    avg={float(d.get('avgMs',0)):.4f}ms p50={float(d.get('p50Ms',0)):.4f}ms p99={float(d.get('p99Ms',0)):.4f}ms avgResults={float(d.get('avgResultCount',0)):.1f}\")
except: print('    ham: $result2')
" 2>/dev/null || true

    # 4-field benchmark
    log "  → 4-field sorgu benchmark ($SEARCH_COUNT sorgu)..."
    local result4
    result4=$(curl -sf --max-time 300 \
        "$BASE_URL/search/benchmark?type=4field&count=$SEARCH_COUNT" 2>/dev/null || echo '{}')
    echo "$result4" > "$SEARCH_DIR/${label}-4field.json"
    python3 -c "
import json, sys
try:
    d = json.loads('''$result4''')
    print(f\"    avg={float(d.get('avgMs',0)):.4f}ms p50={float(d.get('p50Ms',0)):.4f}ms p99={float(d.get('p99Ms',0)):.4f}ms avgResults={float(d.get('avgResultCount',0)):.1f}\")
except: print('    ham: $result4')
" 2>/dev/null || true

    stop_app
}

for DATASET_SIZE in "${DATASET_SIZES[@]}"; do
    for PROVIDER in "${PROVIDERS[@]}"; do
        run_search_scenario "$PROVIDER" "$DATASET_SIZE"
    done
done

log ""
log "════════════════════════════════════════════════"
log "Tüm testler tamamlandı!"
log "Sonuçlar: $RESULTS_DIR/"
log "Arama sonuçları: $SEARCH_DIR/"
log "Özet: $SUMMARY_FILE"
log "Analiz için: python3 scripts/analyze-redis-om.py"
log "════════════════════════════════════════════════"
echo ""
cat "$SUMMARY_FILE"
