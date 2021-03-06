/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package scaps.evaluation.stats

import scaps.settings.Settings

case class Stats(queryStats: List[QueryStats], settings: Settings) {
  val noQueries = queryStats.size
  val meanAveragePrecision = queryStats.map(_.averagePrecision).sum / queryStats.size
  val geometricMeanAveragePrecision = Math.pow(queryStats.foldLeft(1d)(_ * _.averagePrecision), -queryStats.size)
  def meanRecallAt(n: Int) = queryStats.map(_.recallAt(n)).sum / queryStats.size
  val meanDuration = queryStats.map(_.duration).reduce(_ + _) / queryStats.size

  override def toString = s"no. queries: $noQueries, MAP: $meanAveragePrecision, R5: ${meanRecallAt(5)}, R10: ${meanRecallAt(10)}"
}

object Stats {
  implicit val statsOrdering =
    Ordering[Double].on((s: Stats) => s.meanAveragePrecision)
}
