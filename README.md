# Link-d-n-m | Bağlantıdan MP3

Bu depo, **yalnızca YouTube bağlantısından MP3 oluşturma** çalışmasını içerir. Dini Asistan, Kur'an, Mushaf ve tilavet kaynakları bu depoya taşınmaz.

## Proje yapısı
- `app/` — tek ekranlı, Türkçe Android uygulaması (Kotlin)
- `mp3_service/` — izinli video bağlantılarından MP3 elde etmek için yt-dlp + FFmpeg HTTP sunucusu
- `.github/workflows/android-build.yml` — Android birim testleri, Python HTTP testleri ve APK derlemesi

## Telefonda nasıl çalışır?
1. Android uygulamasını aç.
2. İndirme iznin olan bir YouTube video bağlantısını yapıştır.
3. **MP3'e Dönüştür ve İndir** butonuna bas.
4. Android 10 ve üzeri cihazlarda `İndirilenler/LinkMP3` klasörünü kontrol et.

**Önemli:** GitHub tek başına MP3 dönüştürme sunucusu değildir. Bağlantıdan ses üretmek için `mp3_service/` klasöründeki sunucuyu ayrıca HTTPS ile yayımlamak gerekir. Her YouTube videosunun indirilebileceği garanti edilmez.

## Android APK derlemesi
GitHub **Actions → Android MP3 APK** iş akışının başarılı çıktısındaki `link-d-n-m-debug-apk` artifact içinde `app-debug.apk` yer alır. Android Studio veya Java 17 + Gradle 8.10.2 ile `gradle :app:testDebugUnitTest :app:assembleDebug` komutu da kullanılabilir.

## MP3 sunucusu
`mp3_service/README.md` dosyasında Docker kurulumu, HTTPS gereksinimi ve bağımsız test komutları bulunur.

## Sınırlar
- Sadece tekil HTTPS YouTube video bağlantıları.
- Çalma listesi ve diğer siteler kabul edilmez.
- Sunucu özel/yaş sınırlı veya indirme izni olmayan videoları işleyemeyebilir.
- Yalnızca indirme yetkin bulunan içerikleri kullan.
- Gerçek bir YouTube bağlantısıyla uçtan uca test, sunucu dağıtımı ve fiziksel cihaz testi derleme testlerinden ayrıdır.


## Anahtarsız kullanım (10 Ekim 2026)

Android uygulamasında API anahtarı ve sunucu adresi alanları kaldırılmıştır. Resmî HTTPS servis adresi kodda sabittir: `https://link-mp3-api-production.up.railway.app`. Bu adres **gizli bilgi değildir**. Gizli API anahtarları herkese açık kaynak koduna veya APK içine yerleştirilmez. Test sunucusunda kullanıcıdan anahtar istemeyen mod, saatlik genel ve IP başına istek sınırlamasıyla korunur (tek sunucu kopyasında en fazla 12/saat, istemci başına 3/saat). Bu kota kötüye kullanım riskini sıfırlamaz.

Bağlantıyı yapıştırıp **MP3'e Dönüştür ve İndir** düğmesine basabilirsiniz. Canlı YouTube dönüştürme işlemi platform kısıtlamaları ve sunucu durumuna bağlıdır. Sunucuda herhangi bir kesinti veya sınır varsa hata gösterilir. Bu otomatik sunucu gerçek bir arka uçtur, anahtar APK'ya gömülmemiştir.
