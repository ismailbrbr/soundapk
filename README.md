# USB DAC Ses (v2)

Apple USB-C → 3.5 mm DAC gibi USB ses cihazlarının **donanım ses seviyesini** ayarlar.
`guyman624/usbDacVolumeAndroid` v1.0'ın yeniden yazımıdır (Redmi Note 12 Pro 5G sınıfı telefon, Android 12 / MIUI 14 için uyarlandı).

## v1.0'a göre değişenler

| Konu | v1.0 | v2 |
|---|---|---|
| Hedef birim | Sabit: Feature Unit 2, kanal 1-2, arayüz 0 | DAC'ın UAC 1.0/2.0 descriptor'ından okunur; mikrofon yolu ayrıştırılır ve **dokunulmaz** |
| Seviye | Elle 4 haneli little-endian hex | dB cinsinden; DAC'ın min/max/adım aralığına oturtulur |
| Kalite | 0000 yazar (DAC'a göre 0 dB'yi aşabilir) | Hedef **0 dB'yi geçmez** (dijital kırpılma yok); geri okuyup doğrular |
| Ses kesintisi | Her uygulamada arayüz sökülür + reset | Önce **kesintisiz** yöntem denenir (deneysel); doğrulanamazsa klasik yola düşer |
| Bağımlılık | libusb (git submodule, ZIP'te boştu → derlenmiyordu) + AppCompat/Material | Yok. Tek bir `ioctl` yapan 20 satırlık C dosyası; APK çok daha küçük |
| Açılış | Her USB cihazında açılıyordu | Yalnızca USB Audio (AudioControl) cihazlarında |
| Thread | UI thread'inde USB işlemi | Arka plan thread'i, döngü koruması (15 sn) |
| Android 12+ | `PendingIntent` bayrağı 0, receiver dışa açık | `FLAG_MUTABLE` + açık intent, `RECEIVER_NOT_EXPORTED`, `targetSdk 34` |

## Derleme

- **Android Studio**: klasörü aç → Run. (JDK 17, SDK 34; NDK/CMake'i Android Studio kendisi ister.)
- **Komut satırı**: `./gradlew testDebugUnitTest assembleDebug` → `app/build/outputs/apk/debug/`
- **GitHub Actions**: repo'ya yükle, `.github/workflows/build.yml` APK'yı derleyip artifact olarak verir (bilgisayar gerekmez).

Eski `com.example.libusbAndroidTest` uygulamasını kaldır; yoksa USB takınca iki uygulama arasında seçim sorar.

## Kullanım (MIUI 14 / Android 12)

1. DAC'ı tak → açılan pencerede **"Bu USB cihazı için varsayılan olarak kullan"** kutusunu işaretle, izin ver.
2. Uygulamada "Maksimum"u seç, **"DAC takılınca otomatik uygula"**yı aç.
3. Bundan sonra DAC'ı takınca uygulama kendisi açılır, uygular ve kapanır.
4. Takınca açılmazsa (MIUI agresif davranabilir): Ayarlar → Uygulamalar → bu uygulama → *Otomatik başlatma* aç, *Pil tasarrufu* "Kısıtlama yok", varsa *Arka planda açılır pencere* izni.

Ses seviyesini bundan sonra yalnızca telefonun ses tuşlarıyla yönet. DAC sabit 0 dB'de kalır. Başlamadan önce telefon sesini düşük tut.

## Kalite için sistem tarafı (uygulama bunlara dokunmaz)

- MIUI'de Dolby Atmos / Mi Sound / EQ gibi ses efektlerini kapat (kulaklıklar ve ses efektleri menüsü; adı sürüme göre değişir).
- Bit-perfect / yüksek çözünürlük istiyorsan Android 12'nin sistem mikseri bunu sağlamaz; USB-exclusive çalışan bir oynatıcı gerekir. Bu uygulama o alanı değiştirmez.

## Nasıl çalışıyor

UAC Feature Unit "Volume" kontrolü: `SET_CUR`, `wValue = 0x0200 | kanal`, `wIndex = (unit << 8) | arayüz`, 2 bayt, 1/256 dB. Aralık `GET_MIN/MAX/RES` (UAC1) veya `RANGE` (UAC2) ile okunur, değer ızgaraya oturtulur, `GET_CUR` ile doğrulanır.

- **Kesintisiz yol:** istek alıcı bitini "device" ile gönderir. Linux usbfs bu durumda arayüzü sahiplenmeyi şart koşmaz, yani `snd-usb-audio` sökülmez. DAC yazılımı alıcı bitine bakmadan `wIndex`'e göre yönlendiriyorsa çalışır. **Apple DAC'ta çalışıp çalışmadığı gerçek cihazda denenmedi**; çalışmazsa otomatik olarak klasik yola düşer.
- **Klasik yol (v1.0 ile aynı mantık):** AudioControl arayüzü sökülür, ayarlanır, bırakılır, `USBDEVFS_RESET` atılır. Ses birkaç saniye kesilir; DAC ayarı çıkarılana kadar korur.

## Testler

`./gradlew testDebugUnitTest` — descriptor ayrıştırıcı ve ses motoru, sahte bir DAC simülatörüne karşı (UAC1/UAC2, mikrofon ayrımı, 0 dB sınırı, adım oturtma, kesintisiz→klasik geri düşüş, hatada bile reset).

## Lisans / emek

v1.0 LGPL-2.1 lisanslıdır (`LICENSE`), bu türev çalışma da aynı lisansla dağıtılır. Orijinal fikir ve kod: guyman624 (ve onun kaynağı ibaiGorordo).
