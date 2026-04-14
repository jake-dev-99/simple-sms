import '../../interop/permissions_interop.dart';
import 'permissions_enums.dart';
export 'permissions_enums.dart';

/// Manages SMS-related Android permissions and the default SMS app role.
///
/// Use [Intention] to specify which set of permissions to check or request.
/// For sending and receiving SMS/MMS, the app typically needs both the
/// [Intention.texting] permissions and the default SMS app role.
///
/// ```dart
/// // Check if we have SMS permissions
/// final perms = await AndroidPermissions.checkPermissions(Intention.texting);
///
/// // Request the default SMS app role
/// await AndroidPermissions.requestRole(Intention.texting);
/// ```
class AndroidPermissions {
  /// The [Intention] enum for use in permission checks.
  static get intention => Intention;

  /// Requests the system role associated with the given [intent].
  ///
  /// For [Intention.texting], this requests the default SMS app role.
  /// For [Intention.calling], this requests the default dialer role.
  /// Returns true if the role was granted.
  /// Throws if the intention has no associated role.
  static Future<bool> requestRole(Intention intent) async =>
      await PermissionsInterop.requestRole(intent.role!);

  /// Checks whether the app currently holds the role for the given [intent].
  static Future<bool> checkRole(Intention intent) async =>
      await PermissionsInterop.checkRole(intent.role!);

  /// Checks which permissions for the given [intent] are currently granted.
  ///
  /// Returns a map of permission name to granted status.
  static Future<Map<String, bool>> checkPermissions(Intention intent) async =>
      await PermissionsInterop.checkPermissions(intent.permissions);

  /// Requests all permissions associated with the given [intent].
  ///
  /// Shows the system permission dialog for any permissions not yet granted.
  /// Returns a map of permission name to granted status after the request.
  static Future<Map<String, bool>> requestPermissions(
    Intention intent,
  ) async => await PermissionsInterop.requestPermissions(intent.permissions);
}
