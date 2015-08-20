package scaps.webapi

/**
 * Helper methods for de-/encoding entity names.
 *
 * We use a plain string representation for entity names because a more
 * sophisticated representation is too expensive to create during extraction.
 */
object EntityName {
  /**
   * Creates a new name referring to a static member (package/module member)
   * and takes care of the encoding.
   */
  def appendMember(ownerName: String, memberId: String): String =
    if (ownerName == "")
      memberId
    else
      ownerName + '.' + memberId

  /**
   * Splits an encoded name into the decoded identifiers it is composed of.
   * E.g. "pkg.Cls.member" becomes List("pkg", "Cls", "member").
   */
  def splitName(name: String): List[String] = {
    name.split('.').toList
  }
}
