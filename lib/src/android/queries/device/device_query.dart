import '../../models/device/device.dart';
import '../../../interfaces/query_interfaces.dart';

class AndroidDeviceQuery with AndroidDeviceBuilder {
  const AndroidDeviceQuery();

  Future<AndroidDevice> fetch() async {
    return AndroidDevice.fromJson(await super.getDeviceInfo());
  }
}
