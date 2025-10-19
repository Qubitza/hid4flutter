package com.github.vinsfortunato.hid4flutter

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/** Hid4flutterPlugin */
class Hid4flutterPlugin: FlutterPlugin, MethodCallHandler {
  private lateinit var channel : MethodChannel
  private lateinit var applicationContext: Context

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

        // usagePage/usage not available via Android USB APIs directly; default to 0
        val usbManager = applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager

        val deviceList = usbManager.deviceList.values
        val devices = mutableListOf<Map<String, Any>>()
        for (device in deviceList) {

          val interfaceCount = device.interfaceCount
          for (i in 0 until interfaceCount) {
            val intf: UsbInterface = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_HID) {

              if (vendorId != null && device.vendorId != vendorId) continue
              if (productId != null && device.productId != productId) continue

              // Build a stable-ish id: deviceName + interface number
              val id = "${device.deviceName}#${i}"

              // serialNumber, manufacturer, releaseNumber are only available in API 21+
              val manufacturer = try { device.manufacturerName ?: "" } catch (e: Exception) { "" }
              val serialNumber = try { device.serialNumber ?: "" } catch (e: Exception) { "" }
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
              } catch (e: Exception) { 0 }

              val map = hashMapOf<String, Any>(
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
                "busType" to 1 /* 1 = USB */
              )
              devices.add(map)
            }
          }
        }
        result.success(devices)
      }
      else -> result.notImplemented()
    }
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }
}
