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
import android.hardware.usb.UsbDeviceConnection
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

    private data class OpenHandle(
        val connection: UsbDeviceConnection,
        val intf: UsbInterface
    )

    private val openHandles = HashMap<String, OpenHandle>()

    private fun keyFor(path: String, interfaceNumber: Int): String =
        "$" + path + "#" + interfaceNumber

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

            "closeDevice" -> {
                val path = call.argument<String>("path")
                val interfaceNumber = call.argument<Int>("interfaceNumber")
                if (path == null || interfaceNumber == null) {
                    result.error("ARGUMENT_ERROR", "Missing 'path' or 'interfaceNumber'", null)
                    return
                }
                try {
                    closeHidDevice(path, interfaceNumber)
                    result.success(null)
                } catch (e: Exception) {
                    result.error("CLOSE_FAILED", e.message ?: "Failed to close HID device", null)
                }
            }

            "sendReport" -> {
                val path = call.argument<String>("path")
                val interfaceNumber = call.argument<Int>("interfaceNumber")
                val reportId = call.argument<Int>("reportId") ?: 0
                if (path == null || interfaceNumber == null) {
                    result.error("ARGUMENT_ERROR", "Missing 'path' or 'interfaceNumber'", null)
                    return
                }
                try {
                    val raw: ByteArray? = call.argument<ByteArray>("data")
                    val payload: ByteArray = raw ?: ByteArray(0)

                    // Build buffer: first byte is reportId, followed by data bytes
                    val buffer = ByteArray(1 + payload.size)
                    buffer[0] = reportId.toByte()
                    System.arraycopy(payload, 0, buffer, 1, payload.size)

                    sendOutputReport(path, interfaceNumber, buffer)
                    result.success(null)
                } catch (e: IllegalStateException) {
                    result.error("NOT_OPEN", e.message ?: "Device/interface is not open", null)
                } catch (e: Exception) {
                    result.error("SEND_FAILED", e.message ?: "Failed to send HID report", null)
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

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Receiver should not be exported; we only need app/system broadcasts
            Context.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.registerReceiver(receiver, filter, receiverFlags)
        } else {
            @Suppress("DEPRECATION")
            applicationContext.registerReceiver(receiver, filter)
        }

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
        val key = keyFor(path, interfaceNumber)
        if (openHandles.containsKey(key)) {
            return
        }

        val manager = usbManager()
        val device = findDeviceByPath(path)
            ?: throw IllegalArgumentException("Device not found: $path")

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

        val claimed = connection.claimInterface(intf, true)
        if (!claimed) {
            connection.close()
            throw IllegalStateException("Failed to claim interface: $interfaceNumber")
        }

        openHandles[key] = OpenHandle(connection, intf)
    }

    private fun closeHidDevice(path: String, interfaceNumber: Int) {
        val key = keyFor(path, interfaceNumber)
        val handle = openHandles.remove(key) ?: return
        try {
            handle.connection.releaseInterface(handle.intf)
        } catch (_: Exception) {
        }
        try {
            handle.connection.close()
        } catch (_: Exception) {
        }
    }

    private fun findInterruptOutEndpoint(intf: UsbInterface): android.hardware.usb.UsbEndpoint? {
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == UsbConstants.USB_DIR_OUT) {
                return ep
            }
        }
        return null
    }

    private fun sendOutputReport(path: String, interfaceNumber: Int, buffer: ByteArray) {
        val key = keyFor(path, interfaceNumber)
        val handle = openHandles[key]
            ?: throw IllegalStateException("Device/interface not open: $path#$interfaceNumber")
        val timeoutMs = 1000

        if (buffer.isEmpty()) {
            throw IllegalArgumentException("Empty output report buffer: first byte must be the Report ID")
        }

        // Send over out endpoint if available
        val outEp = findInterruptOutEndpoint(handle.intf)
        if (outEp != null) {
            val sent = handle.connection.bulkTransfer(outEp, buffer, buffer.size, timeoutMs)
            if (sent < 0) {
                throw IllegalStateException("bulkTransfer failed with code $sent")
            }
            if (sent != buffer.size) {
                throw IllegalStateException("Only $sent of ${buffer.size} bytes sent")
            }
            return
        }

        // Send over Control Endpoint as report
        val reportId: Int = buffer[0].toInt() and 0xFF
        val payload: ByteArray =
            if (buffer.size > 1) buffer.copyOfRange(1, buffer.size) else ByteArray(0)

        val bmRequestType = 0x21 // Host to device | Class | Interface
        val bRequest = 0x09      // SET_REPORT
        val reportTypeOutput = 0x02
        val wValue = (reportTypeOutput shl 8) or reportId
        val wIndex = handle.intf.id

        val sent = handle.connection.controlTransfer(
            bmRequestType,
            bRequest,
            wValue,
            wIndex,
            payload,
            payload.size,
            timeoutMs
        )
        if (sent < 0) {
            throw IllegalStateException("controlTransfer failed with code $sent")
        }
        if (sent != payload.size) {
            throw IllegalStateException("Only $sent of ${payload.size} bytes sent via controlTransfer")
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }
}
