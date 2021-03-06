/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package scaps.searchEngine.index

import org.scalatest.FlatSpec
import org.scalatest.Matchers
import scaps.searchEngine.ApiTypeQuery._
import scaps.api.Covariant

class TypeFingerprintQuerySpecs extends FlatSpec with Matchers {
  import scaps.searchEngine.index.TypeFingerprintQuery.FingerprintScorer

  def tpe(name: String, boost: Double, freq: Float = 1) = Type(Covariant, name, boost, freq)
  def p(unmatched: Int = 0, unmatchedOpt: Int = 0, unevaluated: Float = 0) =
    unmatched * 1f + unmatchedOpt * 0.1f + unevaluated

  implicit class TupleOps[A, B, C](x: (A, B, C)) {
    def pair = (x._1, x._2)
  }

  "a fingerprint scorer" should "score a simple type query" in {
    val scorer = FingerprintScorer(tpe("A", 1))

    scorer.score(Fingerprint("!+A")).pair should be((1, p()))
    scorer.score(Fingerprint("!+A !+B")).pair should be((1, p(unmatched = 1)))
    scorer.score(Fingerprint("!+B")).pair should be((0, p(unmatched = 1, unevaluated = 1)))
  }

  it should "score sum queries" in {
    val scorer = FingerprintScorer(
      Sum(
        tpe("A", 1),
        tpe("B", 2)))

    scorer.score(Fingerprint("!+A")).pair should be((1, p(unevaluated = 1)))
    scorer.score(Fingerprint("!+A !+B")).pair should be((3, p()))
  }

  it should "score max queries" in {
    val scorer = FingerprintScorer(
      Max(
        tpe("A", 1),
        tpe("B", 2)))

    scorer.score(Fingerprint("!+A")).pair should be((1, p()))
    scorer.score(Fingerprint("!+B")).pair should be((2, p()))
    scorer.score(Fingerprint("!+A !+B")).pair should be((2, p(unmatched = 1)))
    scorer.score(Fingerprint("!+B !+A")).pair should be((2, p(unmatched = 1)))
  }

  it should "allow multiple matches on sum queries that are part of a max query" in {
    val scorer = FingerprintScorer(
      Max(
        tpe("A", 1),
        Sum(
          tpe("B", 2),
          tpe("C", 0.5))))

    scorer.score(Fingerprint("!+A !+B !+C")).pair should be((2.5f, p(unmatched = 1)))
  }

  it should "score repeated types" in {
    val scorer = FingerprintScorer(
      Sum(
        Max(
          tpe("A", 0.9),
          tpe("B", 0.5)),
        tpe("A", 1),
        tpe("A", 1)))

    scorer.score(Fingerprint("!+A !+A")).pair should be((2f, p(unevaluated = 1)))
    scorer.score(Fingerprint("!+A !+B")).pair should be((1.5f, p(unevaluated = 1)))
    scorer.score(Fingerprint("!+A !+A !+A")).pair should be((2.9f, p()))
    scorer.score(Fingerprint("!+B !+A !+A")).pair should be((2.5f, p()))
  }

  it should "score repeated types with varying values" in {
    val scorer = FingerprintScorer(
      Sum(
        Max(
          tpe("A", 0.3),
          tpe("B", 0.5)),
        tpe("A", 1)))

    scorer.score(Fingerprint("!+A !+B")).pair should be((1.5f, p()))
    scorer.score(Fingerprint("!+A !+A !+B")).pair should be((1.5f, p(unmatched = 1)))
  }

  it should "not penalize unmatched optional terms" in {
    val scorer = FingerprintScorer(tpe("A", 1))

    scorer.score(Fingerprint("!+A ?+A ?+B")).pair should be((1f, p(unmatchedOpt = 2)))
    scorer.score(Fingerprint("?+A ?+B")).pair should be((1f, p(unmatchedOpt = 1)))
    scorer.score(Fingerprint("?+B")).pair should be((0f, p(unmatchedOpt = 1, unevaluated = 1)))
  }

  // not relevant because up to now such expression trees do not exists
  ignore should "score sums with a total higher match" in {
    val scorer = FingerprintScorer(
      Max(
        tpe("A", 1.5),
        Sum(
          tpe("B", 1),
          tpe("C", 1))))

    scorer.score(Fingerprint("!+A !+B !+C"))._1 should be(2)
  }

  "the value frequency cutoff" should "not be exceeded" in {
    val values = TypeFingerprintQuery.valuesBelowCutoff(
      Max(tpe("A", 1, 0.5f), tpe("B", 0.9, 0.4f), tpe("C", 0.8, 0.3f)),
      1)

    values should (
      contain("+A") and
      contain("+B") and
      not contain ("+C"))
  }

  it should "fill the quota with values with a lower score" in {
    val values = TypeFingerprintQuery.valuesBelowCutoff(
      Max(tpe("A", 1, 0.5f), tpe("B", 0.9, 0.6f), tpe("C", 0.8, 0.3f)),
      1)

    values should (
      contain("+A") and
      contain("+C") and
      not contain ("+B"))
  }
}
