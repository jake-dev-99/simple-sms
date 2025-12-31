import 'package:flutter_test/flutter_test.dart';
// import 'package:simple_sms/simple_sms_method_channel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  //
  // MethodChannelSimpleSms platform = MethodChannelSimpleSms();
  // const MethodChannel channel = MethodChannel('simple_sms');

  setUp(() {
    // TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
    //   channel,
    //   (MethodCall methodCall) async {
    //     return '42';
    // },
    // );
  });

  tearDown(() {
    // TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });

  test('getPlatformVersion', () async {
    // expect(await platform.getPlatformVersion(), '42');
  });
}
