# VideoSplit

Android için sade, çevrimdışı video bölme uygulaması. Kullanıcının seçtiği videoları 10 saniyelik MP4 parçalara ayırır.

## Özellikler
- Birden fazla video seçip işleme kuyruğuna alma
- Her video için kalıcı proje kartları ve işleme ilerlemesi
- Parça önizlemesi, tek tek indirme ve tamamını klasöre dışa aktarma
- Projeleri silme
- Kaynak dosyaları ve parçaları uygulama içi özel depolamada tutma; galeriye otomatik eklememe

## Kurulum
Android Studio'da açın; JDK 17 ve Android SDK 36 kullanın. Gradle Sync yapıp Android cihazda çalıştırın (minimum Android 8.0).

## Sınırlamalar
- İlk sürüm prototiptir; APK derlemesi ve fiziksel cihaz testleri tamamlanmadı.
- Çoklu video seçilir, ancak videolar aynı anda işlenmek yerine sırayla işlenir.
- İşleme, uygulama arka plandayken kesintisiz devam edecek şekilde servisle henüz desteklenmiyor.
- Uygulama verileri silinir veya uygulama kaldırılırsa projeler ve yerel parçalar silinir.
- Kaynak videolar da özel depolamaya kopyalanır; cihazda alan tüketilir.
