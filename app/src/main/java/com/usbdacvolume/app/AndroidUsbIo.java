package com.usbdacvolume.app;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;

/** {@link UsbIo}'nun Android uygulaması: yalnızca genel (public) UsbDeviceConnection API'si + usbfs reset. */
final class AndroidUsbIo implements UsbIo {
    private final UsbDevice device;
    private final UsbDeviceConnection conn;

    AndroidUsbIo(UsbDevice device, UsbDeviceConnection conn) {
        this.device = device;
        this.conn = conn;
    }

    @Override
    public int control(int requestType, int request, int value, int index, byte[] buf, int length, int timeoutMs) {
        return conn.controlTransfer(requestType, request, value, index, buf, length, timeoutMs);
    }

    @Override
    public boolean claimInterface(int interfaceNumber, boolean force) {
        UsbInterface intf = find(interfaceNumber);
        return intf != null && conn.claimInterface(intf, force);
    }

    @Override
    public boolean releaseInterface(int interfaceNumber) {
        UsbInterface intf = find(interfaceNumber);
        return intf != null && conn.releaseInterface(intf);
    }

    @Override
    public boolean reset() {
        if (NativeUsb.available()) {
            return NativeUsb.nativeReset(conn.getFileDescriptor()) == 0;
        }
        try { // yedek: gizli API, bazı sürümlerde engelli olabilir
            Object r = UsbDeviceConnection.class.getMethod("resetDevice").invoke(conn);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    private UsbInterface find(int number) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getId() == number) return intf;
        }
        return null;
    }
}
