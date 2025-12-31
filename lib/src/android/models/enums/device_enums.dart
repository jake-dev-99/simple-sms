enum AndroidSimCardState {
  unknown(value: 0),
  absent(value: 1),
  pinRequired(value: 2),
  pukRequired(value: 3),
  networkLocked(value: 4),
  ready(value: 5),
  notReady(value: 6),
  permDisabled(value: 7),
  cardIoError(value: 8),
  cardRestricted(value: 9),
  loaded(value: 10),
  present(value: 11);

  const AndroidSimCardState({required this.value});
  final int value;
}
