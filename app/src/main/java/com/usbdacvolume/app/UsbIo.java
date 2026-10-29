package com.usbdacvolume.app;

/**
 * Motorun USB'ye eriştiği en küçük arayüz. Android tarafı {@link AndroidUsbIo}, testlerde sahte DAC.
 */
interface UsbIo {
    /** @return aktarılan bayt sayısı; hata/STALL durumunda negatif. */
    int control(int requestType, int request, int value, int index, byte[] buf, int length, int timeoutMs);

    /** Arayüzü kernel sürücüsünden koparıp (force) sahiplenir. Ses bu sırada kesilir. */
    boolean claimInterface(int interfaceNumber, boolean force);

    boolean releaseInterface(int interfaceNumber);

    /** USB port reset: snd-usb-audio yeniden bağlanır, ses geri gelir. */
    boolean reset();
}
