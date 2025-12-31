/// Conversation/Thread Management Example
///
/// This example demonstrates how to work with conversation threads,
/// which is useful for building a messaging UI similar to native SMS apps.
library;

import 'package:flutter/material.dart';
import 'package:simple_sms/simple_sms.dart';

/// Example: Send a message to an existing conversation thread
Future<void> sendMessageToThread({
  required String conversationId,
  required String message,
  Set<String>? attachmentPaths,
}) async {
  final outboundMessage = OutboundMessage(
    body: message,
    addresses: {}, // Addresses can be empty when using conversationId
    attachmentPaths: attachmentPaths,
    conversationId: conversationId,
  );

  try {
    final result = await AndroidMessaging.instance.sendMessage(
      message: outboundMessage,
    );
    debugPrint('✅ Message sent to conversation $conversationId: $result');
  } catch (e) {
    debugPrint('❌ Failed to send message to conversation: $e');
    rethrow;
  }
}

/// Example: Group all incoming messages by thread
class MessageThreadManager {
  // Store messages organized by thread ID
  final Map<int, List<dynamic>> _threads = {};

  /// Add an SMS to the appropriate thread
  void addSms(Sms sms) {
    final threadId = sms.threadId;
    _threads.putIfAbsent(threadId, () => []);
    _threads[threadId]!.add(sms);

    debugPrint(
      'Added SMS to thread $threadId (${_threads[threadId]!.length} messages)',
    );
  }

  /// Add an MMS to the appropriate thread
  void addMms(Mms mms) {
    final threadId = mms.threadId;
    _threads.putIfAbsent(threadId, () => []);
    _threads[threadId]!.add(mms);

    debugPrint(
      'Added MMS to thread $threadId (${_threads[threadId]!.length} messages)',
    );
  }

  /// Get all messages for a specific thread
  List<dynamic> getThread(int threadId) {
    return _threads[threadId] ?? [];
  }

  /// Get all thread IDs
  List<int> getAllThreadIds() {
    return _threads.keys.toList()..sort((a, b) => b.compareTo(a));
  }

  /// Get the most recent message from each thread
  Map<int, dynamic> getLatestMessages() {
    final latest = <int, dynamic>{};

    for (var entry in _threads.entries) {
      if (entry.value.isNotEmpty) {
        // Sort by date and get the most recent
        final sorted =
            entry.value..sort((a, b) {
              final dateA = a is Sms ? a.date : (a as Mms).date;
              final dateB = b is Sms ? b.date : (b as Mms).date;
              return (dateB ?? DateTime(0)).compareTo(dateA ?? DateTime(0));
            });
        latest[entry.key] = sorted.first;
      }
    }

    return latest;
  }
}

/// Example: Conversation List UI
class ConversationListScreen extends StatefulWidget {
  const ConversationListScreen({super.key});

  @override
  State<ConversationListScreen> createState() => _ConversationListScreenState();
}

class _ConversationListScreenState extends State<ConversationListScreen> {
  final MessageThreadManager _threadManager = MessageThreadManager();

  @override
  void initState() {
    super.initState();
    _initializeMessaging();
  }

  void _initializeMessaging() {
    AndroidMessaging.initialize(
      inboundSmsCallback: (sms) {
        setState(() {
          _threadManager.addSms(sms);
        });
      },
      inboundMmsCallback: (mms) {
        setState(() {
          _threadManager.addMms(mms);
        });
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final latestMessages = _threadManager.getLatestMessages();

    return Scaffold(
      appBar: AppBar(title: const Text('Conversations')),
      body:
          latestMessages.isEmpty
              ? const Center(child: Text('No conversations yet'))
              : ListView.builder(
                itemCount: latestMessages.length,
                itemBuilder: (context, index) {
                  final threadId = latestMessages.keys.elementAt(index);
                  final message = latestMessages[threadId];

                  return ConversationListItem(
                    message: message,
                    onTap: () {
                      Navigator.push(
                        context,
                        MaterialPageRoute(
                          builder:
                              (context) => ConversationScreen(
                                threadId: threadId,
                                messages: _threadManager.getThread(threadId),
                              ),
                        ),
                      );
                    },
                  );
                },
              ),
      floatingActionButton: FloatingActionButton(
        onPressed: () {
          // Navigate to new message screen
        },
        child: const Icon(Icons.message),
      ),
    );
  }
}

/// Widget for a single conversation in the list
class ConversationListItem extends StatelessWidget {
  final dynamic message; // Can be Sms or Mms
  final VoidCallback onTap;

  const ConversationListItem({
    super.key,
    required this.message,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final isMms = message is Mms;
    final address = isMms ? (message as Mms).address : (message as Sms).address;
    final body = isMms ? (message as Mms).body : (message as Sms).body;
    final date = isMms ? (message as Mms).date : (message as Sms).date;
    final hasAttachments =
        isMms &&
        (message as Mms).parts != null &&
        (message as Mms).parts!.isNotEmpty;

    return ListTile(
      leading: CircleAvatar(child: Text(address?.substring(0, 1) ?? '?')),
      title: Text(address ?? 'Unknown'),
      subtitle: Row(
        children: [
          if (hasAttachments) ...[
            const Icon(Icons.attach_file, size: 16),
            const SizedBox(width: 4),
          ],
          Expanded(
            child: Text(
              body ?? 'No message',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
      trailing: Text(
        _formatTime(date),
        style: TextStyle(fontSize: 12, color: Colors.grey[600]),
      ),
      onTap: onTap,
    );
  }

  String _formatTime(DateTime? date) {
    if (date == null) return '';

    final now = DateTime.now();
    final difference = now.difference(date);

    if (difference.inHours < 24) {
      return '${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
    } else if (difference.inDays < 7) {
      const days = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
      return days[date.weekday - 1];
    } else {
      return '${date.day}/${date.month}';
    }
  }
}

/// Example: Individual Conversation/Thread Screen
class ConversationScreen extends StatefulWidget {
  final int threadId;
  final List<dynamic> messages;

  const ConversationScreen({
    super.key,
    required this.threadId,
    required this.messages,
  });

  @override
  State<ConversationScreen> createState() => _ConversationScreenState();
}

class _ConversationScreenState extends State<ConversationScreen> {
  final TextEditingController _messageController = TextEditingController();
  final ScrollController _scrollController = ScrollController();

  @override
  void dispose() {
    _messageController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // Sort messages by date
    final sortedMessages =
        widget.messages..sort((a, b) {
          final dateA = a is Sms ? a.date : (a as Mms).date;
          final dateB = b is Sms ? b.date : (b as Mms).date;
          return (dateA ?? DateTime(0)).compareTo(dateB ?? DateTime(0));
        });

    return Scaffold(
      appBar: AppBar(title: Text(_getConversationTitle())),
      body: Column(
        children: [
          // Messages list
          Expanded(
            child: ListView.builder(
              controller: _scrollController,
              padding: const EdgeInsets.all(8),
              itemCount: sortedMessages.length,
              itemBuilder: (context, index) {
                final message = sortedMessages[index];
                return MessageBubble(message: message);
              },
            ),
          ),

          // Message input
          Container(
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: Colors.grey[100],
              boxShadow: [
                BoxShadow(
                  color: Colors.grey.withValues(alpha: 0.3),
                  blurRadius: 4,
                  offset: const Offset(0, -2),
                ),
              ],
            ),
            child: Row(
              children: [
                IconButton(
                  icon: const Icon(Icons.attach_file),
                  onPressed: _attachFile,
                ),
                Expanded(
                  child: TextField(
                    controller: _messageController,
                    decoration: const InputDecoration(
                      hintText: 'Type a message...',
                      border: InputBorder.none,
                    ),
                    maxLines: null,
                  ),
                ),
                IconButton(
                  icon: const Icon(Icons.send),
                  onPressed: _sendMessage,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  String _getConversationTitle() {
    if (widget.messages.isEmpty) return 'Conversation';

    final firstMessage = widget.messages.first;
    final address =
        firstMessage is Sms
            ? firstMessage.address
            : (firstMessage as Mms).address;

    return address ?? 'Unknown';
  }

  void _attachFile() {
    // Implement file attachment picker
    debugPrint('Attach file tapped');
  }

  void _sendMessage() async {
    final text = _messageController.text.trim();
    if (text.isEmpty) return;

    try {
      await sendMessageToThread(
        conversationId: widget.threadId.toString(),
        message: text,
      );

      if (!mounted) return;

      _messageController.clear();

      // Scroll to bottom
      _scrollController.animateTo(
        _scrollController.position.maxScrollExtent,
        duration: const Duration(milliseconds: 300),
        curve: Curves.easeOut,
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('Failed to send: $e')));
    }
  }
}

/// Widget for a single message bubble
class MessageBubble extends StatelessWidget {
  final dynamic message;

  const MessageBubble({super.key, required this.message});

  @override
  Widget build(BuildContext context) {
    final isMms = message is Mms;
    final body = isMms ? (message as Mms).body : (message as Sms).body;
    final date = isMms ? (message as Mms).date : (message as Sms).date;
    final isIncoming =
        isMms
            ? (message as Mms).type?.value ==
                1 // MESSAGE_TYPE_INBOX
            : (message as Sms).type?.value == 1; // MESSAGE_TYPE_INBOX

    return Align(
      alignment: isIncoming ? Alignment.centerLeft : Alignment.centerRight,
      child: Container(
        margin: const EdgeInsets.symmetric(vertical: 4),
        padding: const EdgeInsets.all(12),
        constraints: BoxConstraints(
          maxWidth: MediaQuery.of(context).size.width * 0.75,
        ),
        decoration: BoxDecoration(
          color: isIncoming ? Colors.grey[300] : Colors.blue[500],
          borderRadius: BorderRadius.circular(16),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (isMms && (message as Mms).subject != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 4),
                child: Text(
                  (message as Mms).subject!,
                  style: TextStyle(
                    fontWeight: FontWeight.bold,
                    color: isIncoming ? Colors.black : Colors.white,
                  ),
                ),
              ),
            Text(
              body ?? '',
              style: TextStyle(color: isIncoming ? Colors.black : Colors.white),
            ),
            if (isMms && (message as Mms).parts != null)
              ...((message as Mms).parts!
                  .where((p) => !p.isText && !p.isSmil)
                  .map(
                    (part) => Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(
                            Icons.attachment,
                            size: 16,
                            color: isIncoming ? Colors.black54 : Colors.white70,
                          ),
                          const SizedBox(width: 4),
                          Text(
                            part.fileName ?? 'Attachment',
                            style: TextStyle(
                              fontSize: 12,
                              color:
                                  isIncoming ? Colors.black54 : Colors.white70,
                            ),
                          ),
                        ],
                      ),
                    ),
                  )),
            const SizedBox(height: 4),
            Text(
              _formatTime(date),
              style: TextStyle(
                fontSize: 10,
                color: isIncoming ? Colors.black54 : Colors.white70,
              ),
            ),
          ],
        ),
      ),
    );
  }

  String _formatTime(DateTime? date) {
    if (date == null) return '';
    return '${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
  }
}
