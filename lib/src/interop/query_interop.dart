import 'dart:async';
import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart';

import '../android/models/queries/query_obj.dart';

class QueryInterop {
  static const MethodChannel _methodChannel = MethodChannel(
    'io.simplezen.simple_sms/query',
  );

  // static const EventChannel _eventChannel = EventChannel(
  //   'io.simplezen.simple_sms/query',
  // );

  static Future<List<Map<String, dynamic>>> query(QueryObj queryObj) async {
    try {
      final result = await _methodChannel.invokeMethod(
        "query",
        queryObj.toJson(),
      );
      final List<Map<String, dynamic>> data =
          result != null
              ? List<Map<String, dynamic>>.from(
                (result as List<dynamic>).map(
                  (item) => Map<String, dynamic>.from(item as Map),
                ),
              )
              : [];
      return data;
    } catch (e, s) {
      debugPrint('Error in query: $e');
      debugPrint(s.toString());
      return [];
    }
  }

  static Future<Map<String, dynamic>> queryAndroidDeviceInfo() async {
    final result = await _methodChannel.invokeMethod<Map>('getDeviceInfo');
    return Map<String, dynamic>.from(result ?? {});
  }

  static Future<List<Map<String, dynamic>>> querySimInfo<T>() async {
    final result = await _methodChannel.invokeMethod<List>("getSimInfo") ?? [];

    final fullResult = List<Map<String, dynamic>>.from(
      result.map((item) => Map<String, dynamic>.from(item as Map)),
    );
    return fullResult;
  }

  static Future<String?> downloadFile(Uri contentUri) async {
    final result = await _methodChannel.invokeMethod<String>("getFile", {
      "uri": contentUri.toString(),
    });
    return result;
  }
}
