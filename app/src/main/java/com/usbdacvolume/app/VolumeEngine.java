package com.usbdacvolume.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DAC donanım ses seviyesini USB Audio Class Feature Unit "Volume" kontrolüyle ayarlar.
 *
 * İki yol dener:
 *  1) KESİNTİSİZ (deneysel): control transfer'i "device" alıcısıyla gönderir. Linux usbfs bu durumda
 *     arayüzü sahiplenmeyi şart koşmaz, yani snd-usb-audio sürücüsü sökülmez ve ses kesilmez. Bazı DAC
 *     yazılımları alıcı bitine bakmadan wIndex'e göre yönlendirir; çalışırsa geri okuyarak doğrularız.
 *  2) KLASİK (orijinal uygulamayla aynı mantık): AudioControl arayüzünü koparır, ayarlar, bırakır ve
 *     USB reset atar. Ses birkaç saniye kesilir.
 *
 * Kalite ilkesi: yalnızca DAC'ın donanım seviyesine dokunulur. Varsayılan hedef 0 dB'dir
 * (DAC aralığının izin verdiği en yüksek, ama 0 dB'yi aşmayan değer): dijital kırpılma yok,
 * dijital kazanç yok. Ses akışına hiçbir işlem eklenmez.
 */
final class VolumeEngine {

    static final int TIMEOUT_MS = 500;
    /** Orijinal uygulamanın sabit kodladığı Feature Unit (descriptor çözülemezse son çare). */
    static final int LEGACY_UNIT = 2;

    private static final int RECIP_DEVICE = 0x00;
    private static final int RECIP_INTERFACE = 0x01;
    private static final int CS_VOLUME = 0x02;

    private VolumeEngine() {}

    static final class Request {
        final boolean max;
        final double db;

        private Request(boolean max, double db) {
            this.max = max;
            this.db = db;
        }

        static Request max() {
            return new Request(true, 0);
        }

        static Request db(double db) {
            return new Request(false, db);
        }
    }

    static final class Report {
        boolean ok;
        boolean verified;
        boolean soundInterrupted;
        String path = "";
        final List<String> lines = new ArrayList<>();
        private String header = "";

        String text() {
            StringBuilder sb = new StringBuilder(header);
            for (String l : lines) sb.append('\n').append(l);
            return sb.toString();
        }
    }

    private static final class Target {
        final int unit;
        final int channel;

        Target(int unit, int channel) {
            this.unit = unit;
            this.channel = channel;
        }
    }

    private static final class Range {
        final int min, max, res;

        Range(int min, int max, int res) {
            this.min = min;
            this.max = max;
            this.res = Math.max(1, res);
        }
    }

    private enum Outcome { VERIFIED, UNVERIFIED, FAILED }

    static Report apply(UsbIo io, UacParser.Info info, Request rq, boolean tryQuiet) {
        Report rep = new Report();
        boolean uac2 = info != null && info.isUac2();
        int acIf = (info != null && info.acInterface >= 0) ? info.acInterface : 0;

        List<Target> targets = new ArrayList<>();
        if (info != null && info.supported()) {
            for (UacParser.FeatureUnit fu : info.playbackUnitsWithVolume()) {
                for (int ch : fu.volumeChannels) targets.add(new Target(fu.id, ch));
            }
        }
        if (targets.isEmpty()) {
            targets.add(new Target(LEGACY_UNIT, 1));
            targets.add(new Target(LEGACY_UNIT, 2));
            rep.lines.add("Çalma yolu descriptor'dan bulunamadı: eski mod (Unit " + LEGACY_UNIT + ", kanal 1-2).");
        }

        if (tryQuiet) {
            List<String> scratch = new ArrayList<>();
            Outcome o = run(io, uac2, acIf, RECIP_DEVICE, targets, rq, scratch);
            if (o == Outcome.VERIFIED) {
                rep.lines.addAll(scratch);
                rep.ok = true;
                rep.verified = true;
                rep.path = "kesintisiz";
                rep.header = "✓ Ses kesilmeden uygulandı ve doğrulandı.";
                return rep;
            }
            rep.lines.add("Kesintisiz yöntem bu DAC'ta doğrulanamadı, klasik yönteme geçildi.");
        }

        if (!io.claimInterface(acIf, true)) {
            rep.header = "✗ AudioControl arayüzü alınamadı.";
            rep.lines.add("Ses gelmiyorsa DAC'ı çıkarıp tekrar takın.");
            return rep;
        }
        rep.soundInterrupted = true;
        rep.path = "klasik";
        Outcome o = run(io, uac2, acIf, RECIP_INTERFACE, targets, rq, rep.lines);
        io.releaseInterface(acIf);
        boolean resetOk = io.reset(); // başarısız olsa da denenir: ses geri gelsin
        rep.lines.add(resetOk
                ? "DAC yeniden başlatıldı; ses birkaç saniye içinde geri gelir (oynatmayı sürdürün)."
                : "USB reset başarısız: ses gelmezse DAC'ı çıkarıp takın.");

        rep.ok = o != Outcome.FAILED;
        rep.verified = o == Outcome.VERIFIED;
        rep.header = o == Outcome.VERIFIED ? "✓ Uygulandı ve doğrulandı (klasik yöntem, ses kısa süre kesildi)."
                : o == Outcome.UNVERIFIED ? "~ Komut gönderildi ama geri okuma ile doğrulanamadı."
                : "✗ Ses seviyesi yazılamadı.";
        return rep;
    }

    private static Outcome run(UsbIo io, boolean uac2, int acIf, int recip,
                               List<Target> targets, Request rq, List<String> out) {
        boolean anyFailed = false;
        boolean allVerified = true;
        for (Target t : targets) {
            Range range = readRange(io, uac2, acIf, recip, t);
            int desired = rq.max ? 0 : (int) Math.round(rq.db * 256.0);
            int target;
            String rangeTxt;
            if (range != null) {
                target = snap(desired, range, rq.max);
                rangeTxt = "aralık " + fmtDb(range.min) + " … " + fmtDb(range.max) + " (adım " + fmtDb(range.res) + ")";
            } else {
                target = Math.max(-32767, Math.min(32767, desired));
                rangeTxt = "aralık okunamadı";
            }
            String who = "Unit " + t.unit + " / kanal " + t.channel + ": ";
            if (!writeVolume(io, recip, acIf, t, target)) {
                out.add(who + "yazma reddedildi (" + rangeTxt + ")");
                anyFailed = true;
                continue;
            }
            Integer cur = readShort(io, uac2 ? 0x01 : 0x81, recip, acIf, t);
            int tol = range != null ? range.res : 1;
            boolean verified = cur != null && Math.abs(cur - target) <= tol;
            if (!verified) allVerified = false;
            out.add(who + fmtDb(target) + (verified ? " ✓" : " (doğrulanamadı)") + " — " + rangeTxt);
        }
        if (anyFailed) return Outcome.FAILED;
        return allVerified ? Outcome.VERIFIED : Outcome.UNVERIFIED;
    }

    /** Hedefi DAC'ın [min,max] aralığına ve adım ızgarasına oturtur. floor=true: asla istenenin üstüne çıkmaz. */
    static int snap(int desired, int min, int max, int res, boolean floor) {
        int step = Math.max(1, res);
        int v = Math.max(min, Math.min(desired, max));
        long k = floor ? Math.floorDiv(v - min, step) : Math.round((double) (v - min) / step);
        int out = (int) (min + k * step);
        if (out > max) out -= step;
        if (out < min) out = min;
        return out;
    }

    private static int snap(int desired, Range r, boolean floor) {
        return snap(desired, r.min, r.max, r.res, floor);
    }

    private static Range readRange(UsbIo io, boolean uac2, int acIf, int recip, Target t) {
        if (!uac2) {
            Integer min = readShort(io, 0x82, recip, acIf, t);
            Integer max = readShort(io, 0x83, recip, acIf, t);
            Integer res = readShort(io, 0x84, recip, acIf, t);
            if (min == null || max == null || min > max) return null;
            return new Range(min, max, res == null ? 1 : res);
        }
        // UAC2: RANGE isteği. Önce alt aralık sayısı, sonra tamamı.
        int value = (CS_VOLUME << 8) | t.channel;
        int index = (t.unit << 8) | acIf;
        byte[] head = new byte[2];
        if (io.control(0xA0 | recip, 0x02, value, index, head, 2, TIMEOUT_MS) < 2) return null;
        int n = (head[0] & 0xff) | ((head[1] & 0xff) << 8);
        if (n < 1 || n > 16) return null;
        byte[] full = new byte[2 + 6 * n];
        if (io.control(0xA0 | recip, 0x02, value, index, full, full.length, TIMEOUT_MS) < full.length) return null;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, res = 1;
        for (int k = 0; k < n; k++) {
            int o = 2 + 6 * k;
            min = Math.min(min, s16(full, o));
            max = Math.max(max, s16(full, o + 2));
            if (k == 0) res = s16(full, o + 4);
        }
        return min > max ? null : new Range(min, max, res);
    }

    private static Integer readShort(UsbIo io, int request, int recip, int acIf, Target t) {
        byte[] b = new byte[2];
        int n = io.control(0xA0 | recip, request, (CS_VOLUME << 8) | t.channel, (t.unit << 8) | acIf, b, 2, TIMEOUT_MS);
        return n == 2 ? s16(b, 0) : null;
    }

    private static boolean writeVolume(UsbIo io, int recip, int acIf, Target t, int value) {
        byte[] d = {(byte) (value & 0xff), (byte) ((value >> 8) & 0xff)};
        return io.control(0x20 | recip, 0x01, (CS_VOLUME << 8) | t.channel, (t.unit << 8) | acIf, d, 2, TIMEOUT_MS) == 2;
    }

    private static int s16(byte[] b, int o) {
        return (short) (((b[o + 1] & 0xff) << 8) | (b[o] & 0xff));
    }

    static String fmtDb(int raw) {
        if (raw <= -32768) return "-∞ dB";
        return String.format(Locale.US, "%.2f dB", raw / 256.0);
    }
}
