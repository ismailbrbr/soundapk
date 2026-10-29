cmake_minimum_required(VERSION 3.10.2)
project("usbdacvolume" C)

add_library(usbdacvolume SHARED usbfs.c)
# Android 15+ (16 KB sayfa) cihazlarla uyum için
set_target_properties(usbdacvolume PROPERTIES LINK_FLAGS "-Wl,-z,max-page-size=16384")
