import 'dart:async';

import 'package:simple_query/simple_query.dart';

import '../enums/sms_mms_enums.dart';

/// What kind of change a [MessageChangeEvent] reports.
///
/// Mirrors `simple_query`'s [ObserveChangeType] but lives at the provider
/// boundary so consumers don't import simple_query types. [unknown] is the
/// honest answer when the underlying ContentObserver couldn't determine the
/// change (Android's `notifyChange` doesn't carry a delta — the platform
/// has to infer one); the consumer reconciles by re-reading the affected
/// rows, which is what it would do for any change type.
enum MessageChangeType {
  /// One or more rows were added.
  created,

  /// One or more rows were modified.
  updated,

  /// One or more rows were removed.
  deleted,

  /// The platform reported a change but couldn't classify it.
  unknown,
}

/// A source-agnostic notification that one or more messages changed in the
/// native store.
///
/// The provider observes `content://sms` and `content://mms` and publishes
/// these events to the host — closing the external-mutation staleness gap
/// (deletes or read-flag flips by other apps) that the broadcast-receiver
/// inbound path does not cover (ADR-0014). Per ADR-0013 the underlying
/// transport is a dedicated change-stream channel, distinct from the read
/// `/query` channel.
///
/// Events carry the affected native row [ids] when the platform supplied
/// them; an empty [ids] list means "something changed in this [channel] —
/// reconcile by re-reading." Consumers should always handle the empty-ids
/// case: Android's `ContentObserver` callback does not include the changed
/// uri's row id, so an [ObserveEvent] from the underlying transport will
/// often have no ids attached.
class MessageChangeEvent {
  const MessageChangeEvent({
    required this.channel,
    required this.changeType,
    required this.timestamp,
    required this.ids,
  });

  /// Which underlying store changed — `content://sms` or `content://mms`.
  final SmsMmsType channel;

  /// What kind of change occurred (created / updated / deleted / unknown).
  final MessageChangeType changeType;

  /// Wall-clock time the change was observed (provider timestamp).
  final DateTime timestamp;

  /// The native row ids that changed, parsed from the raw event. Empty when
  /// the platform could not attribute the change to specific rows — a
  /// reconcile-by-re-read signal, not a malformed event.
  final List<int> ids;

  /// Translate a raw `simple_query` [ObserveEvent] into the normalized event
  /// the host consumes. [channel] is the source the observation was attached
  /// to (the wrapper observes one URI per channel, so it knows which one
  /// emitted the event).
  ///
  /// [ObserveEvent.ids] is typed `List<String>` with no shape guarantee at
  /// the `simple_query` contract level. For SMS/MMS specifically the
  /// `_id` columns on `content://sms` and `content://mms` are integers
  /// (Android Telephony, AOSP `BaseColumns._ID`), so the strings the
  /// platform bridge surfaces are stringified ints — `int.tryParse` round-
  /// trips them. Anything that fails to parse is dropped: the rest of the
  /// event is still a valid reconcile signal (the channel changed; re-read
  /// what matters). A non-integer id arriving here would indicate a
  /// platform-bridge regression, not a contract issue with this translator.
  factory MessageChangeEvent.fromObserveEvent(
    ObserveEvent event, {
    required SmsMmsType channel,
  }) {
    final ids = <int>[
      for (final s in event.ids)
        if (int.tryParse(s) case final n?) n,
    ];
    return MessageChangeEvent(
      channel: channel,
      changeType: _mapChangeType(event.changeType),
      timestamp: event.timestamp,
      // Unmodifiable to preserve the otherwise-immutable value-type contract.
      ids: List.unmodifiable(ids),
    );
  }

  /// Merge multiple per-channel [ObserveEvent] streams into one normalized
  /// stream of [MessageChangeEvent]s, in arrival order. Each [sources] entry
  /// is `(stream, channel)`; the channel tags every event the stream emits.
  ///
  /// The returned stream is **single-subscription** (one subscribe lifecycle
  /// drives all upstream subscriptions). Cancelling the subscription cancels
  /// every upstream subscription; an upstream error forwards through. The
  /// merged stream completes once every source has done so.
  static Stream<MessageChangeEvent> merge(
    List<({Stream<ObserveEvent> stream, SmsMmsType channel})> sources,
  ) {
    late StreamController<MessageChangeEvent> controller;
    final subs = <StreamSubscription<ObserveEvent>>[];
    var doneCount = 0;
    final total = sources.length;

    Future<void> cancelAll() async {
      for (final s in subs) {
        await s.cancel();
      }
      subs.clear();
    }

    void start() {
      if (total == 0) {
        controller.close();
        return;
      }
      for (final source in sources) {
        final channel = source.channel;
        subs.add(source.stream.listen(
          (event) => controller.add(
            MessageChangeEvent.fromObserveEvent(event, channel: channel),
          ),
          onError: controller.addError,
          onDone: () {
            doneCount += 1;
            if (doneCount >= total) controller.close();
          },
        ));
      }
    }

    controller = StreamController<MessageChangeEvent>(
      onListen: start,
      onCancel: cancelAll,
      // Propagate pause/resume to every upstream so paused consumers don't
      // make the controller buffer events the source could have throttled at
      // the producer.
      onPause: () {
        for (final s in subs) {
          s.pause();
        }
      },
      onResume: () {
        for (final s in subs) {
          s.resume();
        }
      },
    );
    return controller.stream;
  }
}

MessageChangeType _mapChangeType(ObserveChangeType t) {
  switch (t) {
    case ObserveChangeType.insert:
      return MessageChangeType.created;
    case ObserveChangeType.update:
      return MessageChangeType.updated;
    case ObserveChangeType.delete:
      return MessageChangeType.deleted;
    case ObserveChangeType.unknown:
      return MessageChangeType.unknown;
  }
}
