package com.usbdacvolume.app;

import java.util.HashMap;
import java.util.Map;

/** Gerçek bir UAC DAC'ın (ve Linux usbfs kurallarının) basit simülasyonu. */
final class FakeDac implements UsbIo {
    static final class Chan {
        int cur;
        final int[][] ranges; // {min,max,res}
        Chan(int cur, int[]... ranges) { this.cur = cur; this.ranges = ranges; }
    }

    final boolean uac2;
    final int acIf;
    final Map<Integer, Chan> chans = new HashMap<>(); // (unit<<8)|channel

    boolean acceptDeviceRecipient = true; // false: kesintisiz yol reddedilir
    boolean getCurWorks = true;
    boolean setWorks = true;

    boolean claimed;
    boolean soundDown;
    int claimCount, releaseCount, resetCount, deviceRecipientSets;

    FakeDac(boolean uac2, int acIf) { this.uac2 = uac2; this.acIf = acIf; }

    void add(int unit, int ch, Chan c) { chans.put((unit << 8) | ch, c); }
    Chan chan(int unit, int ch) { return chans.get((unit << 8) | ch); }

    private static int minAll(Chan c) { int m = Integer.MAX_VALUE; for (int[] r : c.ranges) m = Math.min(m, r[0]); return m; }
    private static int maxAll(Chan c) { int m = Integer.MIN_VALUE; for (int[] r : c.ranges) m = Math.max(m, r[1]); return m; }
    private static void put16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }

    @Override
    public int control(int rt, int req, int value, int index, byte[] buf, int len, int timeout) {
        if ((rt & 0x60) != 0x20) return -1;               // yalnızca class isteği
        int recip = rt & 0x1f;
        boolean in = (rt & 0x80) != 0;
        if (recip == 0 && !acceptDeviceRecipient) return -1;
        if (recip == 1 && !claimed) return -1;             // usbfs: arayüz snd-usb-audio'da, EBUSY
        if (recip != 0 && recip != 1) return -1;
        if ((index & 0xff) != acIf) return -1;
        if ((value >> 8) != 0x02) return -1;               // yalnızca VOLUME_CONTROL
        Chan c = chan(index >> 8, value & 0xff);
        if (c == null) return -1;

        if (!in) {
            if (req != 0x01 || len != 2 || !setWorks) return -1;
            int v = (short) (((buf[1] & 0xff) << 8) | (buf[0] & 0xff));
            if (v < minAll(c) || v > maxAll(c)) return -1;
            c.cur = v;
            if (recip == 0) deviceRecipientSets++;
            return 2;
        }
        if (len != 2 && !(uac2 && req == 0x02)) return -1;
        if (uac2) {
            if (req == 0x01) { if (!getCurWorks) return -1; put16(buf, 0, c.cur); return 2; }
            if (req == 0x02) {
                byte[] all = new byte[2 + 6 * c.ranges.length];
                put16(all, 0, c.ranges.length);
                for (int k = 0; k < c.ranges.length; k++) {
                    put16(all, 2 + 6 * k, c.ranges[k][0]);
                    put16(all, 4 + 6 * k, c.ranges[k][1]);
                    put16(all, 6 + 6 * k, c.ranges[k][2]);
                }
                int n = Math.min(len, all.length);
                System.arraycopy(all, 0, buf, 0, n);
                return n;
            }
            return -1;
        }
        switch (req) {
            case 0x81: if (!getCurWorks) return -1; put16(buf, 0, c.cur); return 2;
            case 0x82: put16(buf, 0, c.ranges[0][0]); return 2;
            case 0x83: put16(buf, 0, c.ranges[0][1]); return 2;
            case 0x84: put16(buf, 0, c.ranges[0][2]); return 2;
            default: return -1;
        }
    }

    @Override public boolean claimInterface(int n, boolean force) {
        if (n != acIf) return false;
        claimed = true; soundDown = true; claimCount++; return true;
    }
    @Override public boolean releaseInterface(int n) { claimed = false; releaseCount++; return true; }
    @Override public boolean reset() { resetCount++; soundDown = false; return true; }
}
