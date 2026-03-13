# CacheCompare

Redis, Hazelcast ve In-Memory HashMap cache mekanizmalarının performansını karşılaştıran Spring Boot PoC projesi.

## Ne yapar?

1000 adet `Product` kaydı üzerinde iki farklı cache stratejisini test eder:

- **Eager** — uygulama başlarken tüm veriyi DB'den çekip cache'e yükler
- **Lazy** — istek geldiğinde cache'e bakar, yoksa DB'den çekip cache'e yazar (cache-aside)

Her kombinasyon çalışma sonunda ölçümler `logs/cache-benchmark.jsonl` dosyasına NDJSON formatında kaydedilir.

## Gereksinimler

- Java 21
- Docker
- Python 3 + `matplotlib`, `numpy` (grafik ve rapor için)

## Kurulum

```bash
# Altyapıyı başlat
docker compose up -d

# Uygulamayı derle
cd cachecompare-web
./mvnw package -q -DskipTests
cd ..

# Veritabanına 1000 ürün ekle (bir kez yeterli)
# Önce uygulamayı herhangi bir profille başlat, seed yap, kapat
java -jar cachecompare-web/target/cachecompare-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=inmemory --cache.strategy=lazy &
sleep 15
curl -X POST http://localhost:8080/seed
kill %1
```

## Kullanım

### Tek kombinasyon çalıştırmak

```bash
java -jar cachecompare-web/target/cachecompare-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=redis \
  --cache.strategy=lazy
```

| Parametre | Değerler |
|---|---|
| `spring.profiles.active` | `redis` · `hazelcast` · `inmemory` |
| `cache.strategy` | `lazy` · `eager` |

Uygulama ayaktayken:

```bash
# Tekli istek
curl http://localhost:8080/products/42

# İç benchmark (1000 istek, network overhead'siz)
curl "http://localhost:8080/products/benchmark/bulk?count=1000"
```

### Tüm kombinasyonları otomatik çalıştırmak

```bash
# İç benchmark — saf cache gecikmesi (ağ yükü dahil değil)
./scripts/full-benchmark.sh internal

# Dış benchmark — gerçek HTTP round-trip süresi
./scripts/full-benchmark.sh external
```

Script her kombinasyon için uygulamayı başlatır, benchmark alır, kapatır.
Lazy testlerinden önce Redis (`FLUSHALL`) ve Hazelcast (container restart) otomatik temizlenir.

### Sonuçları analiz etmek

```bash
python3 scripts/analyze.py
```

Çıktılar `benchmark-results/report/` altına yazılır:

| Dosya | İçerik |
|---|---|
| `metrics.json` | Tüm metrikler (makine okunabilir) |
| `figures/*.png` | 9 adet grafik |
| `report.tex` | LaTeX raporu |

PDF üretmek için:

```bash
cd benchmark-results/report && ./compile.sh
```