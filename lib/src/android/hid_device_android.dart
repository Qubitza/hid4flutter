import 'dart:typed_data';

import 'package:hid4flutter/src/hid_device.dart';
import 'package:hid4flutter/src/hid_exception.dart';

class HidDeviceAndroid extends HidDevice {
  HidDeviceAndroid({
    required this.id,
    required this.path,
    required this.vendorId,
    required this.productId,
    required this.serialNumber,
    required this.releaseNumber,
    required this.manufacturer,
    required this.productName,
    required this.usagePage,
    required this.usage,
    required this.interfaceNumber,
    required this.busType,
  });

  factory HidDeviceAndroid.fromMap(Map<dynamic, dynamic> map) {
    return HidDeviceAndroid(
      id: map['id']?.toString() ?? '',
      path: map['path']?.toString() ?? '',
      vendorId: (map['vendorId'] ?? 0) as int,
      productId: (map['productId'] ?? 0) as int,
      serialNumber: map['serialNumber']?.toString() ?? '',
      releaseNumber: (map['releaseNumber'] ?? 0) as int,
      manufacturer: map['manufacturer']?.toString() ?? '',
      productName: map['productName']?.toString() ?? '',
      usagePage: (map['usagePage'] ?? 0) as int,
      usage: (map['usage'] ?? 0) as int,
      interfaceNumber: (map['interfaceNumber'] ?? 0) as int,
      busType: (map['busType'] ?? 0) as int,
    );
  }

  @override
  final String id;
  @override
  final String path;
  @override
  final int vendorId;
  @override
  final int productId;
  @override
  final String serialNumber;
  @override
  final int releaseNumber;
  @override
  final String manufacturer;
  @override
  final String productName;
  @override
  final int usagePage;
  @override
  final int usage;
  @override
  final int interfaceNumber;
  @override
  final int busType;

  bool _isOpen = false;

  @override
  Future<void> open() async {
    // Not implemented yet
    throw HidException('open() not implemented on Android yet.');
  }

  @override
  bool get isOpen => _isOpen;

  @override
  Future<void> close() async {
    // Not implemented yet
    if (!_isOpen) {
      throw StateError('Device is not open');
    }
    _isOpen = false;
  }

  @override
  Stream<int> inputStream() {
    throw StateError('Device is not open');
  }

  @override
  Future<Uint8List> receiveReport(int reportLength, {Duration? timeout}) {
    throw StateError('Device is not open');
  }

  @override
  Future<void> sendReport(Uint8List data, {int reportId = 0x00}) async {
    throw StateError('Device is not open');
  }

  @override
  Future<Uint8List> receiveFeatureReport(int reportId, {int bufferSize = 1024}) async {
    throw StateError('Device is not open');
  }

  @override
  Future<void> sendFeatureReport(Uint8List data, {int reportId = 0x00}) async {
    throw StateError('Device is not open');
  }

  @override
  Future<String> getIndexedString(int index, {int maxLength = 256}) async {
    throw StateError('Device is not open');
  }
}
