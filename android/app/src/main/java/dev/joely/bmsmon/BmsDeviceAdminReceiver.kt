package dev.joely.bmsmon

import android.app.admin.DeviceAdminReceiver

/**
 * Device-admin receiver required to provision bmsmon as a *device owner*. When the app is the
 * device owner, lock-task mode becomes a true kiosk (no system escape gesture). Provision once
 * on a dedicated phone with no accounts:
 *
 *   adb shell dpm set-device-owner dev.joely.bmsmon/.BmsDeviceAdminReceiver
 *
 * With no provisioning this receiver does nothing and lock-task mode falls back to screen pinning.
 */
class BmsDeviceAdminReceiver : DeviceAdminReceiver()
