#include <jni.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

/*
 * USBDEVFS_RESET: libusb_reset_device() ile aynı çağrı. Port resetlenir, snd-usb-audio
 * arayüzlere yeniden bağlanır ve ses geri gelir. libusb'e artık gerek yok.
 */
JNIEXPORT jint JNICALL
Java_com_usbdacvolume_app_NativeUsb_nativeReset(JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    int r;
    do {
        r = ioctl((int) fd, USBDEVFS_RESET, 0);
    } while (r < 0 && errno == EINTR);
    return r == 0 ? 0 : -errno;
}
