enum MimeType {
  image(value: 'image'),
  video(value: 'video'),
  audio(value: 'audio'),
  application(value: 'application'),
  text(value: 'text'),
  location(value: 'location'),
  unknown(value: 'unknown');

  const MimeType({required this.value});
  final String value;
}
