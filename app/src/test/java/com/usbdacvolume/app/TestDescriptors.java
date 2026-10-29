package com.usbdacvolume.app;

import java.io.ByteArrayOutputStream;

final class TestDescriptors {
    private TestDescriptors() {}

    private static void add(ByteArrayOutputStream o, int... b) { for (int x : b) o.write(x); }

    private static byte[] head(ByteArrayOutputStream body) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        add(o, 18, 1, 0, 2, 0, 0, 0, 64, 0xAC, 0x05, 0x0A, 0x11, 0, 1, 1, 2, 0, 1); // cihaz descriptor'ı
        add(o, 9, 2, 0, 0, 2, 1, 0, 0x80, 50);                                        // konfigürasyon
        byte[] b = body.toByteArray();
        o.write(b, 0, b.length);
        return o.toByteArray();
    }

    /** UAC1: stereo çalma (IT1 USB -> FU2 -> OT3 kulaklık) + mono mikrofon (IT4 -> FU5 -> OT6 USB). */
    static byte[] uac1() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        add(o, 9, 4, 0, 0, 0, 1, 1, 0, 0);                              // AC arayüzü #0
        add(o, 9, 0x24, 1, 0x00, 0x01, 0, 0, 1, 1);                     // header bcdADC 1.00
        add(o, 12, 0x24, 2, 1, 0x01, 0x01, 0, 2, 3, 0, 0, 0);           // IT1 USB streaming
        add(o, 10, 0x24, 6, 2, 1, 1, 0x00, 0x02, 0x02, 0);              // FU2: master yok, ch1+ch2 volume
        add(o, 9, 0x24, 3, 3, 0x02, 0x03, 0, 2, 0);                     // OT3 kulaklık (0x0302)
        add(o, 12, 0x24, 2, 4, 0x01, 0x02, 0, 1, 0, 0, 0, 0);           // IT4 mikrofon (0x0201)
        add(o, 9, 0x24, 6, 5, 4, 1, 0x02, 0x00, 0);                     // FU5: yalnızca master volume
        add(o, 9, 0x24, 3, 6, 0x01, 0x01, 0, 5, 0);                     // OT6 USB streaming
        add(o, 9, 4, 1, 0, 0, 1, 2, 0, 0);                              // AS arayüzü (yok sayılır)
        return head(o);
    }

    /** UAC2: IT1 USB -> FU2 (4 baytlık bmaControls, ch1+ch2 volume R/W) -> OT3 kulaklık. */
    static byte[] uac2() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        add(o, 9, 4, 0, 0, 0, 1, 1, 0x20, 0);
        add(o, 9, 0x24, 1, 0x00, 0x02, 1, 0, 0, 0);                                         // header bcdADC 2.00
        add(o, 8, 0x24, 0x0A, 0x10, 1, 0, 0, 0);                                            // clock source (yok sayılır)
        add(o, 17, 0x24, 2, 1, 0x01, 0x01, 0, 0x10, 2, 0, 0, 0, 0, 0, 0, 0, 0);            // IT1
        add(o, 18, 0x24, 6, 2, 1, 0, 0, 0, 0, 0x0C, 0, 0, 0, 0x0C, 0, 0, 0, 0);            // FU2
        add(o, 12, 0x24, 3, 3, 0x02, 0x03, 0, 2, 0x10, 0, 0, 0);                            // OT3
        return head(o);
    }
}
