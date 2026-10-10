# Link MP3 | Bağımsız dönüştürme sunucusu

Bu klasör, sadece indirme izni olan tekil YouTube videolarındaki sesin MP3 formatına dönüştürülmesi içindir. Android uygulamasının sunucu adresi ve API anahtarı gereksinimini karşılar.

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
