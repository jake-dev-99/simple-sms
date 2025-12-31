import 'package:flutter/material.dart';
import 'dart:async';
import 'package:simple_sms/simple_sms.dart';
import 'package:image_picker/image_picker.dart';

void main() {
  // Initialize the messaging handler with callbacks for incoming messages
  AndroidMessaging.initialize(
    inboundMmsCallback: handleInboundMms,
    inboundSmsCallback: handleInboundSms,
  );

  runApp(const MyApp());
}

// Callback for incoming MMS messages
void handleInboundMms(Mms mms) {
  debugPrint('📨 Received MMS from ${mms.address}');
  debugPrint('   Subject: ${mms.subject}');
  debugPrint('   Body: ${mms.body}');
  debugPrint('   Parts: ${mms.parts?.length ?? 0}');

  // Log each part
  mms.parts?.forEach((part) {
    debugPrint(
      '   - Part: ${part.contentType.value} (${part.fileName ?? "no filename"})',
    );
    if (part.isText) {
      debugPrint('     Text: ${part.text}');
    }
  });
}

// Callback for incoming SMS messages
void handleInboundSms(Sms sms) {
  debugPrint('💬 Received SMS from ${sms.address}');
  debugPrint('   Body: ${sms.body}');
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  bool _hasPermissions = false;
  bool _isDefaultSmsApp = false;
  final List<String> _logs = [];
  final ScrollController _scrollController = ScrollController();

  @override
  void initState() {
    super.initState();
    _checkPermissions();
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  Future<void> _checkPermissions() async {
    if (!mounted) return;

    try {
      final hasRole = await AndroidPermissions.checkRole(Intention.texting);
      final permissions = await AndroidPermissions.checkPermissions(
        Intention.texting,
      );
      final hasFileAccess = await AndroidPermissions.checkPermissions(
        Intention.fileAccess,
      );

      final allGranted =
          permissions.values.every((granted) => granted) &&
          hasFileAccess.values.every((granted) => granted);

      setState(() {
        _isDefaultSmsApp = hasRole;
        _hasPermissions = allGranted;
      });

      _addLog('Permissions check: ${allGranted ? "✅" : "❌"}');
      _addLog('Default SMS app: ${hasRole ? "✅" : "❌"}');
    } catch (e) {
      _addLog('Error checking permissions: $e');
    }
  }

  Future<void> _requestPermissions() async {
    try {
      _addLog('Requesting SMS permissions...');
      final granted = await AndroidPermissions.requestPermissions(
        Intention.texting,
      );

      _addLog('Requesting file access permissions...');
      await AndroidPermissions.requestPermissions(Intention.fileAccess);

      await _checkPermissions();

      if (granted) {
        _addLog('✅ SMS permissions granted');
      } else {
        _addLog('❌ SMS permissions denied');
      }
    } catch (e) {
      _addLog('Error requesting permissions: $e');
    }
  }

  Future<void> _requestDefaultSmsRole() async {
    try {
      _addLog('Requesting default SMS app role...');
      final granted = await AndroidPermissions.requestRole(Intention.texting);

      await _checkPermissions();

      if (granted) {
        _addLog('✅ Default SMS app role granted');
      } else {
        _addLog('❌ Default SMS app role denied');
      }
    } catch (e) {
      _addLog('Error requesting role: $e');
    }
  }

  void _addLog(String message) {
    setState(() {
      _logs.add('[${DateTime.now().toString().substring(11, 19)}] $message');
    });

    // Auto-scroll to bottom
    Future.delayed(const Duration(milliseconds: 100), () {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeOut,
        );
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Simple SMS MMS Example',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
        useMaterial3: true,
      ),
      home: Scaffold(
        appBar: AppBar(
          title: const Text('MMS Example App'),
          backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        ),
        body: Column(
          children: [
            // Status Card
            Card(
              margin: const EdgeInsets.all(16),
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Status',
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        Icon(
                          _hasPermissions ? Icons.check_circle : Icons.cancel,
                          color: _hasPermissions ? Colors.green : Colors.red,
                          size: 20,
                        ),
                        const SizedBox(width: 8),
                        const Text('Permissions'),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Row(
                      children: [
                        Icon(
                          _isDefaultSmsApp ? Icons.check_circle : Icons.cancel,
                          color: _isDefaultSmsApp ? Colors.green : Colors.red,
                          size: 20,
                        ),
                        const SizedBox(width: 8),
                        const Text('Default SMS App'),
                      ],
                    ),
                  ],
                ),
              ),
            ),

            // Permission Buttons
            if (!_hasPermissions || !_isDefaultSmsApp)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Column(
                  children: [
                    if (!_hasPermissions)
                      SizedBox(
                        width: double.infinity,
                        child: ElevatedButton.icon(
                          onPressed: _requestPermissions,
                          icon: const Icon(Icons.security),
                          label: const Text('Grant Permissions'),
                        ),
                      ),
                    if (!_isDefaultSmsApp)
                      SizedBox(
                        width: double.infinity,
                        child: ElevatedButton.icon(
                          onPressed: _requestDefaultSmsRole,
                          icon: const Icon(Icons.sms),
                          label: const Text('Set as Default SMS App'),
                        ),
                      ),
                    const SizedBox(height: 8),
                  ],
                ),
              ),

            // Action Buttons
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Column(
                children: [
                  SizedBox(
                    width: double.infinity,
                    child: ElevatedButton.icon(
                      onPressed: _hasPermissions ? _showSendSmsDialog : null,
                      icon: const Icon(Icons.message),
                      label: const Text('Send SMS'),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: Colors.blue,
                        foregroundColor: Colors.white,
                      ),
                    ),
                  ),
                  const SizedBox(height: 8),
                  SizedBox(
                    width: double.infinity,
                    child: ElevatedButton.icon(
                      onPressed: _hasPermissions ? _showSendMmsDialog : null,
                      icon: const Icon(Icons.photo),
                      label: const Text('Send MMS'),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: Colors.green,
                        foregroundColor: Colors.white,
                      ),
                    ),
                  ),
                  const SizedBox(height: 8),
                  SizedBox(
                    width: double.infinity,
                    child: ElevatedButton.icon(
                      onPressed:
                          _hasPermissions
                              ? _showSendMmsWithMultipleAttachments
                              : null,
                      icon: const Icon(Icons.attach_file),
                      label: const Text('Send MMS (Multiple Attachments)'),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: Colors.purple,
                        foregroundColor: Colors.white,
                      ),
                    ),
                  ),
                ],
              ),
            ),

            const SizedBox(height: 16),

            // Logs Section
            Expanded(
              child: Card(
                margin: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Padding(
                      padding: const EdgeInsets.all(16),
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Text(
                            'Activity Log',
                            style: Theme.of(context).textTheme.titleMedium
                                ?.copyWith(fontWeight: FontWeight.bold),
                          ),
                          IconButton(
                            icon: const Icon(Icons.clear_all),
                            onPressed: () {
                              setState(() {
                                _logs.clear();
                              });
                            },
                            tooltip: 'Clear logs',
                          ),
                        ],
                      ),
                    ),
                    const Divider(height: 1),
                    Expanded(
                      child:
                          _logs.isEmpty
                              ? const Center(
                                child: Text(
                                  'No activity yet',
                                  style: TextStyle(color: Colors.grey),
                                ),
                              )
                              : ListView.builder(
                                controller: _scrollController,
                                padding: const EdgeInsets.all(8),
                                itemCount: _logs.length,
                                itemBuilder: (context, index) {
                                  return Padding(
                                    padding: const EdgeInsets.symmetric(
                                      vertical: 2,
                                    ),
                                    child: Text(
                                      _logs[index],
                                      style: const TextStyle(
                                        fontSize: 12,
                                        fontFamily: 'monospace',
                                      ),
                                    ),
                                  );
                                },
                              ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _showSendSmsDialog() {
    final phoneController = TextEditingController();
    final messageController = TextEditingController();

    showDialog(
      context: context,
      builder:
          (context) => AlertDialog(
            title: const Text('Send SMS'),
            content: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextField(
                  controller: phoneController,
                  decoration: const InputDecoration(
                    labelText: 'Phone Number',
                    hintText: '+1234567890',
                    prefixIcon: Icon(Icons.phone),
                  ),
                  keyboardType: TextInputType.phone,
                ),
                const SizedBox(height: 16),
                TextField(
                  controller: messageController,
                  decoration: const InputDecoration(
                    labelText: 'Message',
                    hintText: 'Enter your message',
                    prefixIcon: Icon(Icons.message),
                  ),
                  maxLines: 3,
                ),
              ],
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('Cancel'),
              ),
              FilledButton(
                onPressed: () {
                  Navigator.pop(context);
                  _sendSms(phoneController.text, messageController.text);
                },
                child: const Text('Send'),
              ),
            ],
          ),
    );
  }

  void _showSendMmsDialog() {
    final phoneController = TextEditingController();
    final messageController = TextEditingController();
    final subjectController = TextEditingController();

    showDialog(
      context: context,
      builder:
          (context) => AlertDialog(
            title: const Text('Send MMS'),
            content: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextField(
                  controller: phoneController,
                  decoration: const InputDecoration(
                    labelText: 'Phone Number',
                    hintText: '+1234567890',
                    prefixIcon: Icon(Icons.phone),
                  ),
                  keyboardType: TextInputType.phone,
                ),
                const SizedBox(height: 16),
                TextField(
                  controller: subjectController,
                  decoration: const InputDecoration(
                    labelText: 'Subject (optional)',
                    hintText: 'MMS Subject',
                    prefixIcon: Icon(Icons.subject),
                  ),
                ),
                const SizedBox(height: 16),
                TextField(
                  controller: messageController,
                  decoration: const InputDecoration(
                    labelText: 'Message',
                    hintText: 'Enter your message',
                    prefixIcon: Icon(Icons.message),
                  ),
                  maxLines: 3,
                ),
              ],
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('Cancel'),
              ),
              FilledButton(
                onPressed: () async {
                  Navigator.pop(context);
                  await _sendMmsWithImage(
                    phoneController.text,
                    messageController.text,
                    subjectController.text.isEmpty
                        ? null
                        : subjectController.text,
                  );
                },
                child: const Text('Pick Image & Send'),
              ),
            ],
          ),
    );
  }

  void _showSendMmsWithMultipleAttachments() {
    final phoneController = TextEditingController();
    final messageController = TextEditingController();

    showDialog(
      context: context,
      builder:
          (context) => AlertDialog(
            title: const Text('Send MMS (Multiple Files)'),
            content: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextField(
                  controller: phoneController,
                  decoration: const InputDecoration(
                    labelText: 'Phone Number',
                    hintText: '+1234567890',
                    prefixIcon: Icon(Icons.phone),
                  ),
                  keyboardType: TextInputType.phone,
                ),
                const SizedBox(height: 16),
                TextField(
                  controller: messageController,
                  decoration: const InputDecoration(
                    labelText: 'Message',
                    hintText: 'Enter your message',
                    prefixIcon: Icon(Icons.message),
                  ),
                  maxLines: 3,
                ),
              ],
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('Cancel'),
              ),
              FilledButton(
                onPressed: () async {
                  Navigator.pop(context);
                  await _sendMmsWithMultipleFiles(
                    phoneController.text,
                    messageController.text,
                  );
                },
                child: const Text('Pick Files & Send'),
              ),
            ],
          ),
    );
  }

  Future<void> _sendSms(String phoneNumber, String message) async {
    if (phoneNumber.isEmpty || message.isEmpty) {
      _addLog('❌ Phone number and message are required');
      return;
    }

    try {
      _addLog('📤 Sending SMS to $phoneNumber...');

      final outboundMessage = OutboundMessage(
        body: message,
        addresses: {phoneNumber},
        attachmentPaths: null,
      );

      final result = await AndroidMessaging.instance.sendMessage(
        message: outboundMessage,
      );

      _addLog('✅ SMS sent successfully');
      _addLog('   Result: ${result.toString()}');
    } catch (e) {
      _addLog('❌ Failed to send SMS: $e');
    }
  }

  Future<void> _sendMmsWithImage(
    String phoneNumber,
    String message,
    String? subject,
  ) async {
    if (phoneNumber.isEmpty) {
      _addLog('❌ Phone number is required');
      return;
    }

    try {
      // Pick an image
      final ImagePicker picker = ImagePicker();
      final XFile? image = await picker.pickImage(source: ImageSource.gallery);

      if (image == null) {
        _addLog('❌ No image selected');
        return;
      }

      _addLog('📤 Sending MMS to $phoneNumber...');
      _addLog('   Message: $message');
      _addLog('   Attachment: ${image.path}');
      if (subject != null) _addLog('   Subject: $subject');

      final outboundMessage = OutboundMessage(
        body: message,
        addresses: {phoneNumber},
        attachmentPaths: {image.path},
      );

      final result = await AndroidMessaging.instance.sendMessage(
        message: outboundMessage,
      );

      _addLog('✅ MMS sent successfully');
      _addLog('   Result: ${result.toString()}');
    } catch (e) {
      _addLog('❌ Failed to send MMS: $e');
    }
  }

  Future<void> _sendMmsWithMultipleFiles(
    String phoneNumber,
    String message,
  ) async {
    if (phoneNumber.isEmpty) {
      _addLog('❌ Phone number is required');
      return;
    }

    try {
      // Pick multiple images
      final ImagePicker picker = ImagePicker();
      final List<XFile> images = await picker.pickMultiImage();

      if (images.isEmpty) {
        _addLog('❌ No images selected');
        return;
      }

      _addLog('📤 Sending MMS to $phoneNumber...');
      _addLog('   Message: $message');
      _addLog('   Attachments: ${images.length} file(s)');

      for (var i = 0; i < images.length; i++) {
        _addLog('   - ${images[i].name}');
      }

      final outboundMessage = OutboundMessage(
        body: message,
        addresses: {phoneNumber},
        attachmentPaths: images.map((img) => img.path).toSet(),
      );

      final result = await AndroidMessaging.instance.sendMessage(
        message: outboundMessage,
      );

      _addLog('✅ MMS sent successfully');
      _addLog('   Result: ${result.toString()}');
    } catch (e) {
      _addLog('❌ Failed to send MMS: $e');
    }
  }
}
