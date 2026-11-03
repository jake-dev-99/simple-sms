/// Purpose: report that a contact has a photo but its bytes could not be read
/// (UNFY-401).
///
/// A contact with no photo is not a failure: the read answers `null` for it.
///
/// @param contactId the contact whose photo could not be read.
/// @throws nothing.
class ContactPhotoReadException implements Exception {
  /// Purpose: describe the failed read of [contactId]'s photo.
  ///
  /// @param contactId the contact whose photo could not be read.
  /// @throws nothing.
  const ContactPhotoReadException(this.contactId);

  /// The contact whose photo could not be read.
  final int contactId;

  /// Purpose: name the failure for logs.
  ///
  /// @returns the exception and the contact id.
  /// @throws nothing.
  @override
  String toString() =>
      'ContactPhotoReadException: no photo bytes for contact $contactId';
}
