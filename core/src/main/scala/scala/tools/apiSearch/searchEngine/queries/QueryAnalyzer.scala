package scala.tools.apiSearch.searchEngine.queries

import scala.Ordering
import scala.tools.apiSearch.model.ClassEntity
import scala.tools.apiSearch.model.Contravariant
import scala.tools.apiSearch.model.Covariant
import scala.tools.apiSearch.model.Invariant
import scala.tools.apiSearch.model.TypeEntity
import scala.tools.apiSearch.model.TypeParameterEntity
import scala.tools.apiSearch.model.Variance
import scala.tools.apiSearch.searchEngine.APIQuery
import scala.tools.apiSearch.searchEngine.NameAmbiguous
import scala.tools.apiSearch.searchEngine.NameNotFound
import scala.tools.apiSearch.searchEngine.SemanticError
import scala.tools.apiSearch.searchEngine.UnexpectedNumberOfTypeArgs
import scala.tools.apiSearch.settings.QuerySettings
import scala.util.Try
import scalaz.\/
import scalaz.std.list.listInstance
import scalaz.syntax.either.ToEitherOps
import scalaz.syntax.traverse.ToTraverseOps
import scala.tools.apiSearch.model.TermEntity
import scala.tools.apiSearch.model.TypeParameterEntity
import scala.tools.apiSearch.model.TypeParameterEntity
import scala.tools.apiSearch.model.TypeParameterEntity

private[queries] sealed trait ResolvedQuery
private[queries] object ResolvedQuery {
  case object Wildcard extends ResolvedQuery
  case class Type(cls: ClassEntity, args: List[ResolvedQuery]) extends ResolvedQuery
}

private[queries] case class FlattenedQuery(types: List[List[FlattenedQuery.Type]])
private[queries] object FlattenedQuery {
  case class Type(variance: Variance, typeName: String, distance: Int, depth: Int)
}

/**
 * Analyzes parsed queries.
 *
 * Instances require access to the class index which can be injected via
 * `findClassesBySuffix` and `findSubClasses`
 */
class QueryAnalyzer private[searchEngine] (
  settings: QuerySettings,
  findClassesBySuffix: (String) => Try[Seq[ClassEntity]],
  findSubClasses: (ClassEntity) => Try[Seq[ClassEntity]]) {

  /**
   * Transforms a parsed query into a query that can be passed to the terms index.
   *
   * Fails when `findClassesBySuffix` or `findSubClasses` fails.
   */
  def apply(raw: RawQuery): Try[SemanticError \/ APIQuery] =
    Try {
      resolveNames(raw.tpe).get.map(
        (normalizeFunctions _) andThen
          (q => flattenQuery(q).get) andThen
          (toApiQuery _) andThen
          { apiQuery => apiQuery.copy(keywords = raw.keywords) })
    }

  /**
   * Resolves all type names in the query and assigns the according class entities.
   *
   * Names that cannot be resolved and have length 1 are treated as type parameters.
   */
  private def resolveNames(raw: RawQuery.Type): Try[SemanticError \/ ResolvedQuery] =
    Try {
      val resolvedArgs: SemanticError \/ List[ResolvedQuery] =
        raw.args.map(arg => resolveNames(arg).get).sequenceU

      resolvedArgs.flatMap { resolvedArgs =>
        findClassesBySuffix(raw.name).get match {
          case Seq() if isTypeParam(raw.name) =>
            ResolvedQuery.Wildcard.right
          case Seq() =>
            NameNotFound(raw.name).left
          case Seq(cls) if resolvedArgs.length == cls.typeParameters.length || raw.args.length == 0 =>
            ResolvedQuery.Type(cls, resolvedArgs).right
          case Seq(cls) =>
            UnexpectedNumberOfTypeArgs(raw.name, cls.typeParameters.length).left
          case candidates =>
            NameAmbiguous(raw.name, candidates).left
        }
      }
    }

  private def isTypeParam(name: String): Boolean =
    name.length() == 1

  //  private def toType(resolved: ResolvedQuery, variance: Variance = Covariant): TypeEntity = {
  //    val ResolvedQuery(cls, args) = resolved
  //    val tpeArgs = cls.typeParameters.zip(args).map {
  //      case (tpeParam, arg) => toType(arg, variance * tpeParam.variance)
  //    }
  //    TypeEntity(cls.name, variance, tpeArgs)
  //  }

  /**
   *
   */
  private def normalizeFunctions(resolved: ResolvedQuery): ResolvedQuery = {
    def uncurry(rq: ResolvedQuery, args: List[ResolvedQuery]): ResolvedQuery =
      rq match {
        case ResolvedQuery.Type(cls, functionArgs) if cls.isFunction =>
          uncurry(functionArgs.last, args ::: functionArgs.init)
        case _ =>
          val typeParams = args.map(_ => TypeParameterEntity("A", Contravariant)) :+ TypeParameterEntity("R", Covariant)
          // this is not a proper ClassEntity but it shouldn't matter as it gets elided
          // in later processing steps
          val function = ClassEntity(TypeEntity.Function.name(args.length), typeParams, Nil)
          ResolvedQuery.Type(function, args :+ rq)
      }

    uncurry(resolved, Nil)
  }

  /**
   * Flattens out a query to a list of types and their alternatives. Hence types at
   * covariant positions are represented as a list `tpe :: subTypes` and types at
   * contravariant positions as `tpe :: superTypes`.
   */
  private def flattenQuery(resolved: ResolvedQuery): Try[FlattenedQuery] =
    Try {
      def flattenWithVarianceAndDepth(variance: Variance, depth: Int, rq: ResolvedQuery): List[(Variance, Int, ClassEntity)] =
        rq match {
          case ResolvedQuery.Wildcard =>
            Nil
          case ResolvedQuery.Type(cls, args) if depth == -1 && cls.isFunction =>
            cls.typeParameters.zip(args).flatMap {
              case (param, arg) => flattenWithVarianceAndDepth(param.variance * variance, depth + 1, arg)
            }
          case ResolvedQuery.Type(cls, args) =>
            val argParts = cls.typeParameters.zip(args).flatMap {
              case (param, arg) => flattenWithVarianceAndDepth(param.variance * variance, depth + 1, arg)
            }
            (variance, depth, cls) :: argParts
        }

      def withAlternatives(variance: Variance, depth: Int, cls: ClassEntity): List[FlattenedQuery.Type] = {
        val alternativesWithDistance: List[(String, Int)] = variance match {
          case Covariant =>
            val subClasses = (findSubClasses(cls).get)
              .map(subCls => (subCls.name, subCls.baseTypes.indexWhere(_.name == cls.name) + 1)).toList
            subClasses :+ ((TypeEntity.Nothing.name, subClasses.size + 1))
          case Contravariant => cls.baseTypes.map(_.name).zipWithIndex.map { case (name, idx) => (name, idx + 1) }
          case Invariant     => Nil
        }

        ((cls.name, 0) :: alternativesWithDistance).map {
          case (alt, distance) => FlattenedQuery.Type(variance, alt, distance, depth)
        }
      }

      val types = flattenWithVarianceAndDepth(Covariant, -1, resolved)
        .map((withAlternatives _).tupled)

      FlattenedQuery(types)
    }

  private def toApiQuery(flattened: FlattenedQuery): APIQuery = {
    val boostsPerType: Map[(Variance, String), List[Float]] =
      flattened.types.flatten.foldLeft(Map[(Variance, String), List[Float]]()) {
        (boostsPerType, tpe) =>
          val key = (tpe.variance, tpe.typeName)
          val boosts = boostsPerType.get(key).getOrElse(Nil)

          boostsPerType + (key -> (boost(tpe) :: boosts))
      }

    val orderedBoostsPerType = boostsPerType.mapValues(_.sorted(Ordering[Float].reverse))

    val types = for {
      ((variance, tpe), distances) <- orderedBoostsPerType
      (boost, idx) <- distances.zipWithIndex
    } yield APIQuery.Type(variance, tpe, idx, boost)

    APIQuery(Nil, types.toList.sortBy(-_.boost))
  }

  private def boost(tpe: FlattenedQuery.Type): Float =
    distanceBoost(tpe.distance) * depthBoost(tpe.depth)

  private def distanceBoost(dist: Int): Float = (1d / (settings.distanceBoostGradient * dist + 1d)).toFloat
  private def depthBoost(depth: Int): Float = (1d / (settings.depthBoostGradient * depth + 1d)).toFloat
}
