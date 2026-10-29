package com.usbdacvolume.app;

final class NativeUsb {
    private static final boolean LOADED;

    static {
        boolean ok;
        try {
            System.loadLibrary("usbdacvolume");
            ok = true;
        } catch (UnsatisfiedLinkError e) {
            ok = false;
        }
        LOADED = ok;
    }

    private NativeUsb() {}

    static boolean available() {
        return LOADED;
    }

    /** @return 0 başarılı, aksi halde -errno */
    static native int nativeReset(int fd);
}
