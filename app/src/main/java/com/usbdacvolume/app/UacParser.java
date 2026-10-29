package com.usbdacvolume.app;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * USB Audio Class (UAC 1.0 / 2.0) AudioControl descriptor ayrıştırıcı.
 *
 * Amaç: orijinal uygulamadaki "Feature Unit = 2, kanal = 1-2, arayüz = 0" varsayımını
 * sabit kodlamak yerine DAC'ın kendi descriptor'larından okumak.
 *
 * Saf Java: Android bağımlılığı yok, JVM üzerinde test edilebilir.
 */
final class UacParser {

    /** USB Streaming terminal tipi (bilgisayar/telefondan gelen PCM akışı). */
    static final int TERM_USB_STREAMING = 0x0101;

    private static final int DT_CONFIG = 0x02;
    private static final int DT_INTERFACE = 0x04;
    private static final int DT_CS_INTERFACE = 0x24;

    private static final int AC_HEADER = 0x01;
    private static final int AC_INPUT_TERMINAL = 0x02;
    private static final int AC_OUTPUT_TERMINAL = 0x03;
    private static final int AC_MIXER_UNIT = 0x04;
    private static final int AC_SELECTOR_UNIT = 0x05;
    private static final int AC_FEATURE_UNIT = 0x06;

    private UacParser() {}

    static final class FeatureUnit {
        final int id;
        final int sourceId;
        /** Ses (volume) kontrolü olan ve yazılabilen kanallar. 0 = master, 1.. = mantıksal kanallar. */
        final List<Integer> volumeChannels = new ArrayList<>();
        /** true: telefondan DAC çıkışına giden (çalma) yolundaki unit. Mikrofon yolu false. */
        boolean playback;

        FeatureUnit(int id, int sourceId) {
            this.id = id;
            this.sourceId = sourceId;
        }
    }

    static final class Info {
        int acInterface = -1;
        int bcdAdc;
        final List<FeatureUnit> featureUnits = new ArrayList<>();

        boolean isUac2() {
            return bcdAdc >= 0x0200 && bcdAdc < 0x0300;
        }

        boolean supported() {
            return acInterface >= 0 && bcdAdc >= 0x0100 && bcdAdc < 0x0300;
        }

        List<FeatureUnit> playbackUnitsWithVolume() {
            List<FeatureUnit> out = new ArrayList<>();
            for (FeatureUnit fu : featureUnits) {
                if (fu.playback && !fu.volumeChannels.isEmpty()) out.add(fu);
            }
            return out;
        }

        String describe() {
            if (!supported()) {
                return "UAC descriptor çözülemedi (eski mod: Unit 2, kanal 1-2 denenecek)";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("UAC ").append(isUac2() ? "2.0" : "1.0")
                    .append(", AudioControl arayüzü #").append(acInterface);
            List<FeatureUnit> pb = playbackUnitsWithVolume();
            if (pb.isEmpty()) {
                sb.append("\nÇalma yolunda ses kontrolü bulunamadı (eski mod denenecek)");
            } else {
                for (FeatureUnit fu : pb) {
                    sb.append("\nÇalma Feature Unit #").append(fu.id).append(", kanallar ").append(fu.volumeChannels);
                }
            }
            return sb.toString();
        }
    }

    static Info parse(byte[] raw) {
        Info info = new Info();
        if (raw == null) return info;

        Map<Integer, int[]> sources = new HashMap<>();
        Map<Integer, Integer> inputTypes = new HashMap<>();
        Map<Integer, Integer> outputTypes = new HashMap<>();

        boolean seenConfig = false;
        boolean inAc = false;
        int i = 0;
        while (i + 2 <= raw.length) {
            int len = u8(raw, i);
            if (len < 2 || i + len > raw.length) break;
            int type = u8(raw, i + 1);
            if (type == DT_CONFIG) {
                if (seenConfig) break; // yalnızca ilk (aktif) konfigürasyon
                seenConfig = true;
            } else if (type == DT_INTERFACE && len >= 9) {
                int num = u8(raw, i + 2);
                int alt = u8(raw, i + 3);
                int cls = u8(raw, i + 5);
                int sub = u8(raw, i + 6);
                boolean isAc = cls == 1 && sub == 1 && alt == 0
                        && (info.acInterface < 0 || info.acInterface == num);
                if (isAc && info.acInterface < 0) info.acInterface = num;
                inAc = isAc;
            } else if (type == DT_CS_INTERFACE && inAc && len >= 3) {
                parseAcDescriptor(raw, i, len, info, sources, inputTypes, outputTypes);
            }
            i += len;
        }
        classify(info, sources, inputTypes, outputTypes);
        return info;
    }

    private static void parseAcDescriptor(byte[] r, int i, int len, Info info,
                                          Map<Integer, int[]> sources,
                                          Map<Integer, Integer> inputTypes,
                                          Map<Integer, Integer> outputTypes) {
        int subtype = u8(r, i + 2);
        switch (subtype) {
            case AC_HEADER:
                if (len >= 5) info.bcdAdc = u8(r, i + 3) | (u8(r, i + 4) << 8);
                break;
            case AC_INPUT_TERMINAL:
                if (len >= 6) inputTypes.put(u8(r, i + 3), u8(r, i + 4) | (u8(r, i + 5) << 8));
                break;
            case AC_OUTPUT_TERMINAL:
                // UAC1 ve UAC2'de bSourceID aynı ofsette (7)
                if (len >= 8) {
                    int id = u8(r, i + 3);
                    outputTypes.put(id, u8(r, i + 4) | (u8(r, i + 5) << 8));
                    sources.put(id, new int[]{u8(r, i + 7)});
                }
                break;
            case AC_MIXER_UNIT:
            case AC_SELECTOR_UNIT:
                if (len >= 5) {
                    int id = u8(r, i + 3);
                    int n = Math.min(u8(r, i + 4), len - 5);
                    int[] s = new int[n];
                    for (int k = 0; k < n; k++) s[k] = u8(r, i + 5 + k);
                    sources.put(id, s);
                }
                break;
            case AC_FEATURE_UNIT:
                parseFeatureUnit(r, i, len, info, sources);
                break;
            default:
                break;
        }
    }

    private static void parseFeatureUnit(byte[] r, int i, int len, Info info, Map<Integer, int[]> sources) {
        if (len < 7) return;
        FeatureUnit fu = new FeatureUnit(u8(r, i + 3), u8(r, i + 4));
        sources.put(fu.id, new int[]{fu.sourceId});

        if (info.isUac2()) {
            // bmaControls[ch]: 4 bayt, ofset 5'ten başlar. Volume = bit 3..2 (11b = okunur+yazılır).
            int count = (len - 6) / 4;
            for (int ch = 0; ch < count; ch++) {
                int off = 5 + ch * 4;
                if (off < len && ((u8(r, i + off) >> 2) & 0x3) == 0x3) fu.volumeChannels.add(ch);
            }
        } else {
            // UAC1: bControlSize = ofset 5, bmaControls[ch] ofset 6'dan başlar. Volume = bit 1.
            int n = u8(r, i + 5);
            if (n > 0) {
                int count = (len - 7) / n;
                for (int ch = 0; ch < count; ch++) {
                    int off = 6 + ch * n;
                    if (off < len && (u8(r, i + off) & 0x02) != 0) fu.volumeChannels.add(ch);
                }
            }
        }
        info.featureUnits.add(fu);
    }

    /** Her Feature Unit'in çalma mı (telefon -> kulaklık) yoksa kayıt mı (mikrofon -> telefon) yolunda olduğunu bulur. */
    private static void classify(Info info, Map<Integer, int[]> sources,
                                 Map<Integer, Integer> inputTypes, Map<Integer, Integer> outputTypes) {
        for (FeatureUnit fu : info.featureUnits) {
            boolean fedByUsb = false;
            for (int id : upstream(fu.id, sources)) {
                Integer t = inputTypes.get(id);
                if (t != null && t == TERM_USB_STREAMING) fedByUsb = true;
            }
            boolean feedsAnalogOut = false;
            for (Map.Entry<Integer, Integer> ot : outputTypes.entrySet()) {
                if (ot.getValue() != TERM_USB_STREAMING && upstream(ot.getKey(), sources).contains(fu.id)) {
                    feedsAnalogOut = true;
                }
            }
            fu.playback = fedByUsb || feedsAnalogOut;
        }
    }

    private static Set<Integer> upstream(int start, Map<Integer, int[]> sources) {
        Set<Integer> seen = new HashSet<>();
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            int id = stack.pop();
            if (!seen.add(id)) continue;
            int[] s = sources.get(id);
            if (s != null) for (int x : s) stack.push(x);
        }
        return seen;
    }

    private static int u8(byte[] a, int i) {
        return a[i] & 0xff;
    }
}
