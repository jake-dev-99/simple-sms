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
  ) async =>
      await methodChannel.invokeMethod<Map<String, bool>>(
        "checkPermissions",
        permissions,
      ) ??
      {};

  static Future<bool> requestPermissions(List<String> permissions) async =>
      await methodChannel.invokeMethod<bool>(
        "requestPermission",
        permissions,
      ) ??
      false;
}
