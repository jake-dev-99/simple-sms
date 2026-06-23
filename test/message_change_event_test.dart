import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:simple_query/simple_query.dart';
import 'package:simple_sms_native/android.dart';

/// Tests for the provider change-stream surface (ADR-0014, UNFY-210):
/// translation of simple_query's [ObserveEvent] into the source-agnostic
/// [MessageChangeEvent], and the multi-channel merge.

ObserveEvent ev(
  ObserveChangeType t, {
  List<String> ids = const [],
  DateTime? at,
}) =>
    ObserveEvent(
      domain: QueryDomain.platformSpecific,
      changeType: t,
      timestamp: at ?? DateTime.fromMillisecondsSinceEpoch(0),
      ids: ids,
    );

void main() {
  group('MessageChangeEvent.fromObserveEvent', () {
    test('maps every ObserveChangeType to the normalized vocabulary', () {
      MessageChangeType convert(ObserveChangeType raw) =>
          MessageChangeEvent.fromObserveEvent(ev(raw), channel: SmsMmsType.sms)
              .changeType;
      expect(convert(ObserveChangeType.insert), MessageChangeType.created);
      expect(convert(ObserveChangeType.update), MessageChangeType.updated);
      expect(convert(ObserveChangeType.delete), MessageChangeType.deleted);
      expect(convert(ObserveChangeType.unknown), MessageChangeType.unknown);
    });

    test('parses numeric ids and drops unparseable strings', () {
      final m = MessageChangeEvent.fromObserveEvent(
        ev(ObserveChangeType.update, ids: ['7', 'oops', '42', '']),
        channel: SmsMmsType.mms,
      );
      expect(m.ids, [7, 42]);
      expect(m.channel, SmsMmsType.mms);
    });

    test('preserves the source channel tag end-to-end', () {
      final mms = MessageChangeEvent.fromObserveEvent(
        ev(ObserveChangeType.insert),
        channel: SmsMmsType.mms,
      );
      expect(mms.channel, SmsMmsType.mms);
      final sms = MessageChangeEvent.fromObserveEvent(
        ev(ObserveChangeType.insert),
        channel: SmsMmsType.sms,
      );
      expect(sms.channel, SmsMmsType.sms);
    });

    test('empty ids list is preserved (reconcile-by-re-read signal)', () {
      final m = MessageChangeEvent.fromObserveEvent(
        ev(ObserveChangeType.update),
        channel: SmsMmsType.sms,
      );
      expect(m.ids, isEmpty);
      expect(m.changeType, MessageChangeType.updated);
    });
  });

  group('MessageChangeEvent.merge', () {
    test('multiplexes per-channel events in arrival order with channel tags',
        () async {
      final smsCtl = StreamController<ObserveEvent>();
      final mmsCtl = StreamController<ObserveEvent>();
      final merged = MessageChangeEvent.merge([
        (stream: smsCtl.stream, channel: SmsMmsType.sms),
        (stream: mmsCtl.stream, channel: SmsMmsType.mms),
      ]);

      final received = <MessageChangeEvent>[];
      final sub = merged.listen(received.add);

      smsCtl.add(ev(ObserveChangeType.insert, ids: ['1']));
      mmsCtl.add(ev(ObserveChangeType.update, ids: ['9']));
      smsCtl.add(ev(ObserveChangeType.delete, ids: ['2']));
      await Future<void>.delayed(Duration.zero);

      // Project to (channel, changeType, idsCsv) — record equality falls
      // back to List reference equality for nested lists, so flatten the
      // ids into a primitive String for the deep-equals match.
      String key(MessageChangeEvent e) =>
          '${e.channel.name}|${e.changeType.name}|${e.ids.join(",")}';
      expect(received.map(key).toList(), [
        'sms|created|1',
        'mms|updated|9',
        'sms|deleted|2',
      ]);

      await sub.cancel();
      await smsCtl.close();
      await mmsCtl.close();
    });

    test('completes only after every source completes', () async {
      final a = StreamController<ObserveEvent>();
      final b = StreamController<ObserveEvent>();
      var done = false;
      MessageChangeEvent.merge([
        (stream: a.stream, channel: SmsMmsType.sms),
        (stream: b.stream, channel: SmsMmsType.mms),
      ]).listen((_) {}, onDone: () => done = true);

      await a.close();
      await Future<void>.delayed(Duration.zero);
      expect(done, false, reason: 'still waiting on the other source');
      await b.close();
      await Future<void>.delayed(Duration.zero);
      expect(done, true);
    });

    test('forwards upstream errors through the merged stream', () async {
      final ctl = StreamController<ObserveEvent>();
      Object? caught;
      final sub = MessageChangeEvent.merge([
        (stream: ctl.stream, channel: SmsMmsType.sms),
      ]).listen((_) {}, onError: (e) => caught = e);

      ctl.addError(StateError('boom'));
      await Future<void>.delayed(Duration.zero);

      expect(caught, isA<StateError>());
      await sub.cancel();
      await ctl.close();
    });

    test('cancelling the merged subscription detaches every upstream', () async {
      final a = StreamController<ObserveEvent>();
      final b = StreamController<ObserveEvent>();
      final sub = MessageChangeEvent.merge([
        (stream: a.stream, channel: SmsMmsType.sms),
        (stream: b.stream, channel: SmsMmsType.mms),
      ]).listen((_) {});

      await Future<void>.delayed(Duration.zero); // let listens attach
      expect(a.hasListener, true);
      expect(b.hasListener, true);

      await sub.cancel();
      expect(a.hasListener, false);
      expect(b.hasListener, false);

      await a.close();
      await b.close();
    });

    test('empty source list yields an immediately-done stream', () async {
      final events = await MessageChangeEvent.merge([]).toList();
      expect(events, isEmpty);
    });
  });
}
