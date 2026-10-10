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

## v2.0 PRO geliştirme
- MP3 kalite seçimi 128/192/320 kbps, dönüştürme ve indirme durumları, indirmeyi iptal etme.
- MP3 arşivi: çalma/duraklatma, ileri sarma, paylaşma ve silme.
- Android 10+ İndirilenler/LinkMP3; Android 8-9 uygulama özel müzik dizini.
- YouTube bağlantısını diğer uygulamalardan paylaşma; kayıt sırasında yarım MP3'leri temizleme.
- Otomatik birim, HTTP ve debug APK testleri.
- Çalışan v1 yedeği: `backup/v1-working-20261010`.

**Sınırlar:** Debug APK imzası GitHub Actions iş akışlarında kalıcı olmayabilir. Üzerine kurularak güncellenen final sürümler için sabit ve güvenli imza anahtarı gerekir. Bu projede APK içerisine gizli API anahtarı konmaz. YouTube'un erişim engelleri veya bot kontrolleri dönüşümü engelleyebilir.


## v2.3 | Fotoğraf + ses ile video oluşturma

- **Tek ekranda** fotoğraf seç, indirilen MP3 veya telefondan ses seç, **1/2/3/4 dakika** süre belirle, **Video Oluştur** düğmesine bas.
- Fotoğraf otomatik **720×1280 (9:16)** siyah arka plan üzerinde boyutlandırılır; MP4 **H.264 görüntü + AAC ses** olarak Android cihazda oluşturulur. Fotoğraf ve ses sunucuya yüklenmez.
- MP3 indirildikten sonra Video Oluştur kısmındaki ses seçimi otomatik güncellenir.
- Girişteki orkestra bölümünü atlamak için **yerel, yaklaşık ses analizi** başlangıç saniyesi önerir; **insan sesini her zaman doğru tespit ettiği iddia edilmez**. Kullanıcı başlangıç saniyesini sürgüyle düzeltip önizleme dinleyebilir.
- Seçilen süreden uzun ses kırpılır, kısa ses gerektiğinde döngüye alınır.
- Android 10+ kayıt konumu **Movies/MuzikVideolarim**; Android 8–9 uygulamaya özel Movies. Galeri üzerinden açılabilir.
- İşlem sırasında yüzde, iptal ve hata mesajı vardır.
- Android cihazların video kodlayıcı performansı farklıdır; özellikle dört dakika süren işlem zaman alabilir. Gerçek MP4 oluşturma ve sese uygun video uzunluğu Android cihaz testleri ile teyit edilmelidir.
- Eski MP3 indirme kodu korunur; video oluşturma yalnızca aynı sayfaya eklenir.

Bu projeyi yalnızca indirme, kopyalama veya paylaşma hakkınızın bulunduğu içeriklerle kullanın.

## v2.4: YouTube kısıtlamasında yerel medya dosyasını MP3'e dönüştür

- CAPTCHA / 'Ben robot değilim' geliştirmesi kullanıcı kararıyla iptal edilmiştir. Üretimde Turnstile etkin değildir.
- YouTube URL → MP3 işlemi korunur. YouTube'un Railway sunucu IP'sine uyguladığı bot/giriş engeli devam edebilir. Uygulamadaki CAPTCHA bunu çözmez.
- Alternatif: TELEFONDAN VİDEO / SES → MP3. Kullanıcının 60 MB'a kadar dosyası dönüştürme için sunucuya yüklenir; FFmpeg ilk ses akışından 192 kbps MP3 oluşturur. Geçici giriş/çıkış dosyaları işlem sonunda silinir.
- MP3, Android Downloads/LinkMP3 ve aynı ekrandaki video ses listesine eklenir.
- Güvenlik: izin verilen medya MIME türleri, dosya boyut sınırı, saatlik istek sınırı, maksimum iki eşzamanlı dönüştürme, FFmpeg zaman aşımı, dosya protokolü sınırı ve video yerine yalnız ses seçimi. Özel dosyalar sunucuya gönderilmemeli.
- HTTP API: POST /convert-file; başlık Content-Type video/mp4 vb., body dosya baytları, çıktı audio/mpeg. Hatalar 413 / 415 / 422.
- Docker CI: gerçek test videosu üret, endpoint ile dönüştür, sonucu ffprobe ile MP3 ve süre doğrula.
- Android cihaz testi: video-*.mp3 kaydet, listele, baytları oku ve sil. Ek olarak MP3 ve MP4 testleri korunur.
- Farklı paket: com.kadir.muzikindir.video24, eski debug APK imzasıyla çakışmadan yan yana kurulur. Önceki sürümün MP3'leri Android depolama izinleri yüzünden yeni listede otomatik görünmeyebilir.

## v2.5: Vokal girişinde geç başlama sorununun düzeltmesi

- Eski otomatik tespit sesi kullanıcının onayı olmadan şarkının ortasına atabiliyordu. Artık varsayılan ses başlangıcı 00:00.0 ve analiz sonucu **asla otomatik uygulanmaz**.
- İlk ses tahmini bulunursa kısa bir 1,8 saniyelik ön payla önerilir. **Tahmini dinle** ve **Tahmini kullan** düğmeleriyle seçilir. Bir algoritmanın şarkıcı sesi ile orkestrayı her parçada kesin ayırması mümkün değildir.
- Manuel başlangıç 0,5 saniye çözünürlüklü çubuk ve -2/-0,5/+0,5/+2 saniye düğmeleriyle ayarlanır. İlk kelime kaçırıldıysa geriye alın.
- MP3 önizlemesi artık seek işlemi tamamlanmadan başlamaz. Kullanıcının seçtiği milisaniye ile MP4 export clipping başlangıcı aynıdır.
- İndirilen MP3, telefondan ses seçimi, 1/2/3/4 dakika video, video kaydı, paylaşım ve önceki yerel dosya→MP3 özelliği korunur. Bot/CAPTCHA kodu yok.
- Birim testleri: hatalı/geç başlangıç, 1,8 sn geriden öneri, 0,5 sn ayar, uç nokta sınırı, önizleme ve export senkronizasyonu.
- Önceki test APK'sıyla imza çatışmasına karşı ayrı `com.kadir.muzikindir.video25` paket kimliği kullanılır. Eski APK silinmez, ancak önceki MP3 indeksleri yeni pakete otomatik taşınmayabilir.
