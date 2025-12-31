/// Advanced MMS Example
///
/// This example shows advanced MMS handling including:
/// - Processing different content types (images, video, audio, text)
/// - Extracting and displaying MMS parts
/// - Handling SMIL presentations
/// - Working with MMS metadata
library;

import 'package:flutter/material.dart';
import 'package:simple_sms/simple_sms.dart';

/// Process an incoming MMS message and extract all information
void processInboundMms(Mms mms) {
  debugPrint('=' * 60);
  debugPrint('📨 MMS RECEIVED');
  debugPrint('=' * 60);

  // Basic information
  debugPrint('Message ID: ${mms.id}');
  debugPrint('Thread ID: ${mms.threadId}');
  debugPrint('From: ${mms.address ?? mms.sender?.address ?? "Unknown"}');
  debugPrint('Subject: ${mms.subject ?? "No Subject"}');
  debugPrint('Date: ${mms.date}');
  debugPrint('Body: ${mms.body}');

  // Recipients
  if (mms.recipients != null && mms.recipients!.isNotEmpty) {
    debugPrint('\nRecipients:');
    for (var recipient in mms.recipients!) {
      debugPrint(
        '  - ${recipient.address} (${recipient.participantType?.value})',
      );
    }
  }

  // Sender information
  if (mms.sender != null) {
    debugPrint('\nSender:');
    debugPrint('  Address: ${mms.sender!.address}');
    debugPrint('  Type: ${mms.sender!.participantType?.value}');
  }

  // Process MMS parts
  if (mms.parts != null && mms.parts!.isNotEmpty) {
    debugPrint('\nMMS Parts (${mms.parts!.length} total):');

    for (var i = 0; i < mms.parts!.length; i++) {
      final part = mms.parts![i];
      debugPrint('\n  Part ${i + 1}:');
      debugPrint('    ID: ${part.id}');
      debugPrint('    Content Type: ${part.contentType.value}');
      debugPrint('    File Name: ${part.fileName ?? "N/A"}');
      debugPrint('    Location: ${part.contentLocation}');

      if (part.isText) {
        // Text part
        debugPrint('    Type: TEXT');
        debugPrint('    Text: ${part.text ?? "No text"}');
        debugPrint('    Charset: ${part.charset?.value ?? "N/A"}');
      } else if (part.isSmil) {
        // SMIL presentation (layout/timing info)
        debugPrint('    Type: SMIL PRESENTATION');
      } else {
        // Media attachment (image, video, audio, etc.)
        debugPrint('    Type: MEDIA ATTACHMENT');
        debugPrint('    Data Location: ${part.dataLocation}');
        debugPrint('    Size: ${part.sequence ?? "N/A"}');

        // Identify specific media types
        final contentType = part.contentType.value.toLowerCase();
        if (contentType.contains('image')) {
          debugPrint('    Media Type: IMAGE');
        } else if (contentType.contains('video')) {
          debugPrint('    Media Type: VIDEO');
        } else if (contentType.contains('audio')) {
          debugPrint('    Media Type: AUDIO');
        } else if (contentType.contains('application')) {
          debugPrint('    Media Type: APPLICATION/FILE');
        }
      }
    }
  }

  // Additional metadata
  debugPrint('\nMetadata:');
  debugPrint('  Read: ${mms.read}');
  debugPrint('  Seen: ${mms.seen ?? "N/A"}');
  debugPrint('  Message Box: ${mms.messageBox?.value ?? "N/A"}');
  debugPrint('  Message Type: ${mms.type?.value ?? "N/A"}');
  debugPrint('  Status: ${mms.status?.value ?? "N/A"}');
  debugPrint('  Priority: ${mms.priority?.value ?? "N/A"}');
  debugPrint('  Message Size: ${mms.messageSize ?? "N/A"} bytes');
  debugPrint('  Content Type: ${mms.contentType ?? "N/A"}');

  debugPrint('=' * 60);
}

/// Extract images from an MMS message
List<MmsPart> extractImages(Mms mms) {
  if (mms.parts == null) return [];

  return mms.parts!.where((part) {
    final contentType = part.contentType.value.toLowerCase();
    return contentType.contains('image/');
  }).toList();
}

/// Extract videos from an MMS message
List<MmsPart> extractVideos(Mms mms) {
  if (mms.parts == null) return [];

  return mms.parts!.where((part) {
    final contentType = part.contentType.value.toLowerCase();
    return contentType.contains('video/');
  }).toList();
}

/// Extract audio from an MMS message
List<MmsPart> extractAudio(Mms mms) {
  if (mms.parts == null) return [];

  return mms.parts!.where((part) {
    final contentType = part.contentType.value.toLowerCase();
    return contentType.contains('audio/');
  }).toList();
}

/// Extract all text content from an MMS message
String extractAllText(Mms mms) {
  if (mms.parts == null) return mms.body;

  final textParts = mms.parts!.where((part) => part.isText && !part.isSmil);

  if (textParts.isEmpty) return mms.body;

  return textParts
      .map((part) => part.text ?? '')
      .where((text) => text.isNotEmpty)
      .join('\n\n');
}

/// Example: Display MMS in a Flutter widget
class MmsMessageWidget extends StatelessWidget {
  final Mms mms;

  const MmsMessageWidget({super.key, required this.mms});

  @override
  Widget build(BuildContext context) {
    final images = extractImages(mms);
    final videos = extractVideos(mms);
    final allText = extractAllText(mms);

    return Card(
      margin: const EdgeInsets.all(8),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Header
            Row(
              children: [
                CircleAvatar(
                  child: Text(mms.sender?.address?.substring(0, 1) ?? '?'),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        mms.sender?.address ?? 'Unknown',
                        style: const TextStyle(fontWeight: FontWeight.bold),
                      ),
                      if (mms.subject != null && mms.subject!.isNotEmpty)
                        Text(
                          mms.subject!,
                          style: TextStyle(
                            fontSize: 12,
                            color: Colors.grey[600],
                          ),
                        ),
                    ],
                  ),
                ),
                Text(
                  _formatDate(mms.date),
                  style: TextStyle(fontSize: 12, color: Colors.grey[600]),
                ),
              ],
            ),

            const SizedBox(height: 12),

            // Text content
            if (allText.isNotEmpty) Text(allText),

            // Images
            if (images.isNotEmpty) ...[
              const SizedBox(height: 12),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children:
                    images.map((img) {
                      return Container(
                        width: 100,
                        height: 100,
                        decoration: BoxDecoration(
                          color: Colors.grey[300],
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            const Icon(Icons.image, size: 40),
                            Text(
                              img.fileName ?? 'Image',
                              style: const TextStyle(fontSize: 10),
                              overflow: TextOverflow.ellipsis,
                            ),
                          ],
                        ),
                      );
                    }).toList(),
              ),
            ],

            // Videos
            if (videos.isNotEmpty) ...[
              const SizedBox(height: 12),
              ...videos.map((video) {
                return ListTile(
                  leading: const Icon(Icons.video_library),
                  title: Text(video.fileName ?? 'Video'),
                  subtitle: Text(video.contentType.value),
                  dense: true,
                );
              }),
            ],

            // Attachment count
            if (mms.parts != null && mms.parts!.length > 1)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  '${mms.parts!.length} attachments',
                  style: TextStyle(fontSize: 12, color: Colors.grey[600]),
                ),
              ),
          ],
        ),
      ),
    );
  }

  String _formatDate(DateTime? date) {
    if (date == null) return '';

    final now = DateTime.now();
    final difference = now.difference(date);

    if (difference.inMinutes < 1) {
      return 'Just now';
    } else if (difference.inHours < 1) {
      return '${difference.inMinutes}m ago';
    } else if (difference.inDays < 1) {
      return '${difference.inHours}h ago';
    } else if (difference.inDays < 7) {
      return '${difference.inDays}d ago';
    } else {
      return '${date.day}/${date.month}/${date.year}';
    }
  }
}

/// Example: Send MMS with different media types
class AdvancedMmsSender {
  /// Send an MMS with mixed media (image + video)
  static Future<void> sendMixedMediaMms({
    required Set<String> recipients,
    required String message,
    List<String> imagePaths = const [],
    List<String> videoPaths = const [],
    List<String> audioPaths = const [],
  }) async {
    final allAttachments = <String>{
      ...imagePaths,
      ...videoPaths,
      ...audioPaths,
    };

    final outboundMessage = OutboundMessage(
      body: message,
      addresses: recipients,
      attachmentPaths: allAttachments,
    );

    try {
      debugPrint('Sending MMS with:');
      debugPrint('  - ${imagePaths.length} images');
      debugPrint('  - ${videoPaths.length} videos');
      debugPrint('  - ${audioPaths.length} audio files');

      final result = await AndroidMessaging.instance.sendMessage(
        message: outboundMessage,
      );

      debugPrint('✅ MMS sent successfully: $result');
    } catch (e) {
      debugPrint('❌ Failed to send MMS: $e');
      rethrow;
    }
  }

  /// Send an MMS with a specific priority
  static Future<void> sendPriorityMms({
    required Set<String> recipients,
    required String message,
    Set<String>? attachments,
  }) async {
    // Note: Priority is typically set by the carrier/system
    // This example shows the structure
    final outboundMessage = OutboundMessage(
      body: '🔴 URGENT: $message',
      addresses: recipients,
      attachmentPaths: attachments,
    );

    await AndroidMessaging.instance.sendMessage(message: outboundMessage);
  }
}
