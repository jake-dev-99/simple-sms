import '../../interop/permissions_interop.dart';
import 'permissions_enums.dart';
export 'permissions_enums.dart';

class AndroidPermissions {
  static get intention => Intention;

  /// Request the necessary role for the given intent
  static Future<bool> requestRole(Intention intent) async =>
      await PermissionsInterop.requestRole(intent.role!);

  static Future<bool> checkRole(Intention intent) async =>
      await PermissionsInterop.checkRole(intent.role!);

  static Future<Map<String, bool>> checkPermissions(Intention intent) async =>
      await PermissionsInterop.checkPermissions(intent.permissions);

  static Future<bool> requestPermissions(Intention intent) async =>
      await PermissionsInterop.requestPermissions(intent.permissions);
}
