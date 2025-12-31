
/// Represents different intentions for permission requests
enum Intention {
  texting,
  calling,
  contacts,
  device,
  fileAccess;

  /// Get the Android role associated with this intention
  String? get role {
    switch (this) {
      case Intention.texting:
        return 'android.app.role.SMS';
      case Intention.calling:
        return 'android.app.role.DIALER';
      case Intention.contacts:
      case Intention.device:
      case Intention.fileAccess:
        return null;
    }
  }

  /// Get the Android permissions associated with this intention
  List<String> get permissions {
    switch (this) {
      case Intention.texting:
        return [
          'android.permission.SEND_SMS',
          'android.permission.READ_SMS',
          'android.permission.RECEIVE_SMS',
          'android.permission.WRITE_SMS',
          'android.permission.RECEIVE_WAP_PUSH',
          'android.permission.RECEIVE_MMS',
        ];
      case Intention.calling:
        return [
          'android.permission.READ_PHONE_STATE',
          'android.permission.READ_PHONE_NUMBERS',
        ];
      case Intention.contacts:
        return [
          'android.permission.WRITE_CONTACTS',
          'android.permission.READ_CONTACTS',
          'android.permission.MANAGE_OWN_CALLS',
        ];
      case Intention.device:
        return ['Manifest.permission.READ_DEVICE_CONFIG'];
      case Intention.fileAccess:
        return [
          'android.permission.READ_EXTERNAL_STORAGE',
          'android.permission.READ_MEDIA_IMAGES',
          'android.permission.READ_MEDIA_VIDEO',
          'android.permission.READ_MEDIA_AUDIO',
        ];
    }
  }
}
