package com.github.vinsfortunato.hid4flutter

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Hid4flutterPlugin */
class Hid4flutterPlugin : FlutterPlugin, MethodCallHandler {
    private lateinit var channel: MethodChannel
    private lateinit var applicationContext: Context

    private val ACTION_USB_PERMISSION by lazy { "${applicationContext.packageName}.USB_PERMISSION" }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = flutterPluginBinding.applicationContext
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "hid4flutter")
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "getDevices" -> {
                val vendorId = call.argument<Int>("vendorId")
                val productId = call.argument<Int>("productId")
                val devices = listHidDevices(vendorId, productId)
                result.success(devices)
            }

            "openDevice" -> {
                val path = call.argument<String>("path")
                val interfaceNumber = call.argument<Int>("interfaceNumber")
                if (path == null || interfaceNumber == null) {
                    result.error("ARGUMENT_ERROR", "Missing 'path' or 'interfaceNumber'", null)
                    return
                }
                try {
                    openHidDevice(path, interfaceNumber)
                    result.success(null)
                } catch (se: SecurityException) {
                    result.error("PERMISSION_DENIED", se.message ?: "USB permission denied", null)
                } catch (e: Exception) {
                    result.error("OPEN_FAILED", e.message ?: "Failed to open HID device", null)
                }
            }

            else -> result.notImplemented()
        }
    }

    private fun usbManager(): UsbManager =
        applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private fun listHidDevices(vendorId: Int?, productId: Int?): List<Map<String, Any>> {
        val usbManager = usbManager()
        val deviceList = usbManager.deviceList.values
        val devices = mutableListOf<Map<String, Any>>()
        for (device in deviceList) {
            val interfaceCount = device.interfaceCount
            for (i in 0 until interfaceCount) {
                val intf: UsbInterface = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_HID) {
                    if (vendorId != null && device.vendorId != vendorId) continue
                    if (productId != null && device.productId != productId) continue
                    devices.add(buildDeviceMap(device, intf, i))
                }
            }
        }
        return devices
    }

    private fun buildDeviceMap(
        device: UsbDevice,
        intf: UsbInterface,
        interfaceIndex: Int
    ): Map<String, Any> {
        val id = "${device.deviceName}#${interfaceIndex}"
        val manufacturer = try {
            device.manufacturerName ?: ""
        } catch (e: Exception) {
            ""
        }
        val serialNumber = try {
            device.serialNumber ?: ""
        } catch (e: Exception) {
            ""
        }
        val productName = device.productName ?: (device.deviceName ?: "")
        val releaseNumber = try {
            val v = device.version
            if (v != null) {
                val parts = v.split('.')
                val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val minorStr = parts.getOrNull(1) ?: "0"
                val minor = minorStr.padEnd(2, '0').take(2).toIntOrNull() ?: 0
                major * 256 + minor
            } else 0
        } catch (e: Exception) {
            0
        }

        return hashMapOf(
            "id" to id,
            "path" to device.deviceName,
            "vendorId" to device.vendorId,
            "productId" to device.productId,
            "serialNumber" to serialNumber,
            "releaseNumber" to releaseNumber,
            "manufacturer" to manufacturer,
            "productName" to productName,
            "usagePage" to 0,
            "usage" to 0,
            "interfaceNumber" to intf.id,
            "busType" to 1
        )
    }

    private fun findDeviceByPath(path: String): UsbDevice? {
        val manager = usbManager()
        return manager.deviceList.values.firstOrNull { it.deviceName == path }
    }

    private fun requestUsbPermission(device: UsbDevice): Boolean {
        val manager = usbManager()
        if (manager.hasPermission(device)) return true

        val latch = CountDownLatch(1)
        var granted = false

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_USB_PERMISSION) {
                    val d: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (d != null && d.deviceId == device.deviceId) {
                        granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        try {
                            applicationContext.unregisterReceiver(this)
                        } catch (_: Exception) {
                        }
                        latch.countDown()
                    }
                }
            }
        }

        applicationContext.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION))

        val flags = when {
            Build.VERSION.SDK_INT >= 31 -> PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else -> PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(
            applicationContext,
            0,
            Intent(ACTION_USB_PERMISSION),
            flags
        )

        manager.requestPermission(device, permissionIntent)

        // Wait up to 5 seconds for user response
        latch.await(5, TimeUnit.SECONDS)
        return granted || manager.hasPermission(device)
    }

    private fun openHidDevice(path: String, interfaceNumber: Int) {
        val manager = usbManager()
        val device =
            findDeviceByPath(path) ?: throw IllegalArgumentException("Device not found: $path")

        if (!manager.hasPermission(device)) {
            val ok = requestUsbPermission(device)
            if (!ok) {
                throw SecurityException("Missing USB permission for device: $path")
            }
        }

        val intf = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == interfaceNumber }
            ?: throw IllegalArgumentException("Interface not found: $interfaceNumber")

        val connection = manager.openDevice(device)
            ?: throw IllegalStateException("Failed to open device: $path")

        try {
            val claimed = connection.claimInterface(intf, true)
            if (!claimed) {
                throw IllegalStateException("Failed to claim interface: $interfaceNumber")
            }
            // For now we just verify we can claim and then release/close immediately.
            connection.releaseInterface(intf)
        } finally {
            connection.close()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }
}
