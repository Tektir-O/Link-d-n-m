# Link MP3 | Bağımsız dönüştürme sunucusu

Bu klasör, sadece indirme izni olan tekil YouTube videolarındaki sesin MP3 formatına dönüştürülmesi içindir. Android uygulamasına otomatik bağlanan çevrimiçi servis olarak kullanılabilir.

## Docker ile çalıştırma

1. Docker kurulu sunucuda bu klasöre girin.
2. Güçlü ve rastgele bir anahtar oluşturun (en az 16 karakter).
3. docker build -t link-mp3 .
4. docker run --rm -p 127.0.0.1:8080:8080 -e LINK_MP3_API_KEY=GIZLI_RASTGELE_ANAHTAR link-mp3
5. Caddy/nginx veya barındırma sağlayıcısının HTTPS proxy'siyle servisi internete güvenli açın. 8080 portunu doğrudan herkese açık hale getirmeyin.
6. Telefon uygulamasında HTTPS sunucu URL'sini ve aynı anahtarı girin.

API:
- GET /health: sunucu durumu
- POST /convert: Authorization: Bearer YOUR_KEY, Content-Type: application/json, gövde {"url":"https://youtu.be/VIDEO_ID"}
- Başarı: audio/mpeg olarak MP3 dosyası. Hatalarda JSON.

## Testler

python3 -m unittest -v test_app

Testler canlı YouTube'a bağlanmaz. Bağlantı doğrulama, API anahtarı, yanlış bağlantı reddi, dönüştürme komutu ve sahte MP3 cevapları test edilir.

**Uyarı:** Her YouTube videosunun indirilebileceği garanti edilmez. Yaş/ülke/erişim engelleri ve platform politikaları geçerlidir. Bu kod bir sunucuda yayına alınmadan Android uygulaması tek başına bağlantıdan MP3 oluşturmaz. API anahtarlarını GitHub'a veya APK içine koymayın.


## Anahtar girmeden çalışan test sunucusu

Resmî sunucu `https://link-mp3-api-production.up.railway.app` adresindedir. Mobil istemci bu alan adını otomatik kullanır; sunucu bilgisi ve gizli API anahtarı istemez. Sunucuda `LINK_MP3_PUBLIC_MODE=1` ortam değişkeni etkinleştirilirse anonim istekler kabul edilir ve IP başına **3/saat**, tüm sunucu genelinde **12/saat** kota uygulanır. Kota Python işlem belleğinde tutulur; yeniden başlatmada sıfırlanır ve çoklu örnekler arasında paylaşılmaz. Daha yoğun/hassas kullanımda merkezi hız sınırı, erişim kontrolü ve abuse tespiti eklenmelidir.

Sunucuyu **özel modda** çalıştırmak için `LINK_MP3_PUBLIC_MODE` değişkenini kaldırıp uzun bir `LINK_MP3_API_KEY` sunucu ortam değişkeni atayın. Gizli değerleri APK veya GitHub'a yüklemeyin. Android'in bu sürümü yalnızca anonim modla çalışır.

## v2.0
`POST /convert`: `{"url":"https://youtu.be/VIDEO_ID","quality":192}`. Desteklenen kaliteler: **128, 192, 320 kbps**. Varsayılan 192. Kaynak ses kalitesini yükseltmez.
Maksimum süre 90 dakika, MP3 dosyası en çok 100 MB, kaynak dosya sınırlaması 80 MB.

Docker derleme: `docker build -t linkmp3-v2 ./mp3_service`.
Docker konteynerindeki yt-dlp/FFmpeg kurulum süresi dağıtım aşamasındadır; Railway'in mevcut üretim servisi henüz bu Docker imajına geçirilmiş değildir. Canlı dağıtım ve cihaz üzerinde test ayrıca yapılmalıdır.


## YouTube erişimi ve desteklenen hata tipleri (Ekim 2026)

- `YOUTUBE_VERIFICATION_REQUIRED`: YouTube sunucuya bot/giriş doğrulaması uyguladı. HTTP 503 + `Retry-After: 600`. Bu bir APK, FFmpeg veya klasör hatası değildir. Arka arkaya otomatik deneme yapılmaz.
- `EXTRACTOR_UPDATE_NEEDED`: YouTube JavaScript/biçim ayrıştırma güncellemesi gerekli olabilir. HTTP 503 + `Retry-After: 300`.
- `/health` yalnızca servis çalışıyor mu sorusuna yanıt verir; YouTube tarafından videoya izin verildiğini **garanti etmez**.
- Deno, yt-dlp'nin desteklediği JavaScript çalışma ortamıdır. Docker imajı Deno ve yt-dlp'nin `default` bağımlılıklarındaki EJS bileşenini birlikte içerir.
- Gerektiğinde `LINK_MP3_REMOTE_EJS=1` ile resmi `ejs:npm` bileşeni kullanılabilir; ek ağ gerektirir. Bu seçenek, sunucu IP'sine uygulanan giriş doğrulamasını ortadan kaldırma garantisi değildir.
- Giriş/kullanıcı çerezlerini APK'ya veya GitHub'a koymayın. Şarkı/video indirme hakkı bulunan içeriklerde kullanın.
- Railway'de kaynak olarak **GitHub repo bağlantısı ayrı izin ister**. Bağlanmadıysa Docker imajı testlerde başarılı olsa bile canlı Railway servisi eski kaynak imajını kullanmaya devam edebilir.

Testler: `cd mp3_service && python -m unittest -v test_app.py`. CI, Docker imajının Deno / EJS / FFmpeg araçlarını, `/health` yanıtını, geçersiz URL reddini ve Android MP3/video testlerini denetler.
