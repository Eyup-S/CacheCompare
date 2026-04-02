# CacheCompare

Redis, Hazelcast ve In-Memory cache mekanizmalarını; ardından RedisTemplate ile Redis OM for Spring'i gerçek yükler altında karşılaştıran Spring Boot PoC projesi.

## Modüller

| Modül | Karşılaştırma | Port |
|---|---|---|
| `cachecompare-web` | Redis · Hazelcast · InMemory | 8080 |
| `cachecompare-redis-om` | RedisTemplate (Lettuce) · Redis OM (Jedis) | 8081 |

---

## Modül 1 — `cachecompare-web`

Redis, Hazelcast ve InMemory cache üzerinde **ID bazlı** erişim performansını ölçer.

### Gereksinimler

- Java 21 · Docker
- Python 3 + `matplotlib`, `numpy` (grafik ve rapor için)

### Altyapı

```bash
docker compose up -d redis hazelcast postgres
```

### Derleme & Seed

```bash
cd cachecompare-web
./mvnw package -q -DskipTests
cd ..

# PostgreSQL'e 1 kez seed yap
java -jar cachecompare-web/target/cachecompare-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=inmemory --cache.strategy=lazy &
sleep 15
curl -X POST http://localhost:8080/seed
kill %1
```

### Tek kombinasyon

```bash
java -jar cachecompare-web/target/cachecompare-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=redis \
  --cache.strategy=lazy
```

| Parametre | Değerler |
|---|---|
| `spring.profiles.active` | `redis` · `hazelcast` · `inmemory` |
| `cache.strategy` | `lazy` · `eager` |

```bash
curl http://localhost:8080/products/42
curl "http://localhost:8080/products/benchmark/bulk?count=1000"
```

### Tüm kombinasyonları çalıştır

```bash
./scripts/full-benchmark.sh internal   # saf cache gecikmesi
./scripts/full-benchmark.sh external   # gerçek HTTP round-trip
```

### Analiz & Rapor

```bash
python3 scripts/analyze.py
cd benchmark-results/report && ./compile.sh   # → report.pdf
```

---

## Modül 2 — `cachecompare-redis-om`

**RedisTemplate (Lettuce)** ile **Redis OM for Spring (Jedis/RediSearch)** arasında iki farklı senaryo üzerinde karşılaştırma yapar:

1. **ID bazlı cache** — `GET product:{id}` / `JSON.GET ProductDocument:{id}`
2. **Alan bazlı arama** — category + brand (2-field) ve + price/stock aralığı (4-field)

### Gereksinimler

- Java 21 · Docker
- Python 3 (grafik için `matplotlib`, `numpy` opsiyonel)

### Altyapı

```bash
docker compose up -d redis-stack postgres
```

> `redis-stack` **RedisJSON** ve **RediSearch** modüllerini içerir. Standart Redis yeterli değildir.
> Port: **6380** (Redis Stack) · **5432** (PostgreSQL)

### Derleme

```bash
cd cachecompare-redis-om
./mvnw package -q -DskipTests
cd ..
```

### Tam Benchmark (önerilen)

Script her kombinasyonu otomatik başlatır, ölçer ve kapatır. Hem ID cache hem de alan bazlı arama senaryolarını içerir.

```bash
./scripts/redis-om-benchmark.sh
```

**Çalışan senaryolar:**

| # | Provider | Dataset | Strateji | Açıklama |
|---|---|---|---|---|
| 1–2 | RedisTemplate | 1k | lazy / eager | SET/GET, Lettuce |
| 3–4 | RedisTemplate | 200k | lazy / eager | SET/GET, Lettuce |
| 5–6 | Redis OM | 1k | lazy / eager | JSON.SET/GET, Jedis |
| 7–8 | Redis OM | 200k | lazy / eager | JSON.SET/GET, Jedis |
| 9–12 | RedisTemplate | 1k / 200k | — | SINTER+HGETALL arama |
| 13–16 | Redis OM | 1k / 200k | — | FT.SEARCH arama |

### Manuel çalıştırma

```bash
# redis-template profili — lazy strateji, 1k dataset
java -jar cachecompare-redis-om/target/cachecompare-redis-om-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=redis-template \
  --cache.strategy=lazy \
  --dataset.size=1000 \
  --server.port=8081

# redis-om profili — eager strateji, 200k dataset
java -jar cachecompare-redis-om/target/cachecompare-redis-om-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=redis-om \
  --cache.strategy=eager \
  --dataset.size=200000 \
  --server.port=8081
```

| Parametre | Değerler |
|---|---|
| `spring.profiles.active` | `redis-template` · `redis-om` |
| `cache.strategy` | `lazy` · `eager` |
| `dataset.size` | `1000` · `200000` (veya istediğiniz değer) |

### Uç noktalar

| Uç nokta | Açıklama |
|---|---|
| `GET /products/{id}` | Tekil ürün okuma (cache benchmark) |
| `GET /products/benchmark/bulk?count=N` | İç ID bazlı benchmark (N istek) |
| `POST /search/load` | Arama store'una veri yükle |
| `GET /search/benchmark?type=2field&count=N` | 2-field arama benchmark |
| `GET /search/benchmark?type=4field&count=N` | 4-field arama benchmark |
| `GET /warmup/status` | Eager warm-up durumu |

### Analiz & Rapor

```bash
python3 scripts/analyze-redis-om.py
cd benchmark-results-redis-om/report && ./compile.sh   # → report.pdf
```

Rapor çıktıları `benchmark-results-redis-om/report/` altına yazılır:

| Dosya | İçerik |
|---|---|
| `report.pdf` | 21 sayfalık LaTeX raporu (tablo + analiz) |
| `metrics.json` | Tüm metrikler (makine okunabilir) |
| `figures/*.png` | Grafikler (matplotlib gerektirir) |

### Temel Bulgular

**ID bazlı cache (eager, warm cache):**
- p50 latency her iki provider'da neredeyse özdeş (~0.10 ms)
- 200k'da Redis OM ~%17 daha hızlı (Jedis senkron model daha düşük std)
- Warm-up (yazma) hızında RedisTemplate `multiSet` pipeline avantajlı

**Alan bazlı arama (200k kayıt):**

| Sorgu | RedisTemplate | Redis OM | Fark |
|---|---|---|---|
| 2-field (category + brand) | ~146 ms avg | ~6.3 ms avg | **~23x** |
| 4-field (+ price + stock) | ~131 ms avg | ~2.3 ms avg | **~56x** |

RedisTemplate SINTER+HGETALL zinciri eşleşen kayıt sayısına doğrusal bağımlı (O(N) komut).
Redis OM `FT.SEARCH` tek komutla tüm sonuçları döndürür; dataset büyüdükçe avantaj artar.

---

## Proje Yapısı

```
cachecompare/
├── cachecompare-web/           # Modül 1: Redis/Hazelcast/InMemory
├── cachecompare-redis-om/      # Modül 2: RedisTemplate vs Redis OM
│   └── src/main/java/.../
│       ├── cache/              # Cache stratejileri (lazy/eager)
│       ├── config/             # RedisOMConfig, RedisHashConfig
│       ├── controller/         # ProductController, SearchBenchmarkController
│       ├── entity/             # Product, ProductDocument (@Document), ProductHash (@RedisHash)
│       ├── repository/         # ProductDocumentRepository, ProductHashRepository
│       ├── search/             # SearchStrategy, RedisOmSearchStrategy, RedisHashSearchStrategy
│       └── service/            # SearchBenchmarkService
├── scripts/
│   ├── full-benchmark.sh       # Modül 1 benchmark
│   ├── redis-om-benchmark.sh   # Modül 2 benchmark (ID cache + arama)
│   ├── analyze.py              # Modül 1 analiz
│   └── analyze-redis-om.py     # Modül 2 analiz + LaTeX raporu
├── benchmark-results/          # Modül 1 sonuçları
├── benchmark-results-redis-om/ # Modül 2 sonuçları + rapor
└── docker-compose.yml          # postgres, redis, hazelcast, redis-stack
```

## Teknik Notlar

- `redis-om-spring 0.9.6`'da `BigDecimal` alanı `@Indexed` ile NUMERIC index olarak derlenemiyor; bu nedenle `price` filtresi arama benchmark'ında Java katmanında uygulanır.
- `@EnableRedisDocumentRepositories(basePackages)` hem repository hem de entity paketini içermeli; aksi hâlde `FT.CREATE` çalışmaz.
- `docker exec <container> redis-cli FLUSHALL` gerektirir; host'ta `redis-cli` kurulu olmayabilir.
