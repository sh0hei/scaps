package scaps.nucleus.statistics

import scaps.nucleus.Document
import scaps.nucleus.MetaDoc
import scaps.nucleus.ValueDoc
import upickle._
import scaps.nucleus.IndexAccess
import scaps.nucleus.indexing.TypeView
import scaps.nucleus.MetaDoc
import scala.language.higherKinds
import scaps.nucleus.indexing.FingerprintTerm
import scaps.nucleus.indexing.TypeNormalization
import scaps.nucleus.LanguageSettings
import scaps.nucleus.TypeDef
import scaps.nucleus.indexing.Fingerprint

private[nucleus] class FrequencyAggregator(val languageModel: LanguageSettings, index: IndexAccess) {
  def typeFrequencyDocs(): Iterable[Document] = {
    typeFrequencies().map {
      case (term, count) =>
        MetaDoc(List(s"<tf>:$term"), write(count).getBytes, "")
    }
  }

  def typeFrequencies(): Map[String, Int] = {
    val views = index.getByKeys(Seq("<v>")).map { doc =>
      read[TypeView](new String(doc.data))
    }

    val types = index.getByKeys(Seq("<t>")).map { doc =>
      read[TypeDef](new String(doc.data))
    }

    typeFrequencies(types, views)
  }

  def typeFrequencies(types: Seq[TypeDef], views: Seq[TypeView]): Map[String, Int] = {
    val elementary = types.flatMap(t => TypeView.elementaryTypeViews(t.typeParameters, t.tpe))
    val allViews = elementary ++ views

    val viewsByFrom = allViews.groupBy(_.from).toIterable

    viewsByFrom.flatMap {
      case (from, views) =>
        val fp = Fingerprint(from).map(_.key)

        val noMatchingDocs = index.countByKeys(fp)

        if (noMatchingDocs > 0)
          views.flatMap { v =>
            val toFp = Fingerprint(v.to).map(_.key)
            toFp.map(term => (term, noMatchingDocs))
          }
        else
          Nil
    }.foldLeft(Map[String, Int]().withDefaultValue(0)) { (acc, tf) =>
      val prev = acc(tf._1)
      acc + (tf._1 -> (tf._2 + prev))
    }
  }
}
