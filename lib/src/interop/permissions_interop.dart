import 'dart:async';
import 'package:flutter/services.dart';

class PermissionsInterop {
  static const MethodChannel methodChannel = MethodChannel(
    'io.simplezen.simple_sms/permissions',
  );

  static Future<bool> requestRole(String role) async =>
      await methodChannel.invokeMethod<bool>("requestRole", role) ?? false;

  static Future<bool> checkRole(String role) async =>
      await methodChannel.invokeMethod<bool>("checkRole", role) ?? false;

  static Future<Map<String, bool>> checkPermissions(
    List<String> permissions,
  ) async {
    final result = await methodChannel.invokeMethod<Map>(
      "checkPermissions",
      permissions,
    );
    if (result == null) return {};
    return Map<String, bool>.from(result);
  }

  static Future<Map<String, bool>> requestPermissions(
    List<String> permissions,
  ) async {
    final result = await methodChannel.invokeMethod<Map>(
      "requestPermission",
      permissions,
    );
    if (result == null) return {};
    return Map<String, bool>.from(result);
  }
}
