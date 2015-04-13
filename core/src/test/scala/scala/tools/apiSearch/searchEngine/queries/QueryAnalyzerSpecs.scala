package scala.tools.apiSearch.searchEngine
package queries

import scala.collection.immutable.Map
import scala.tools.apiSearch.featureExtraction.ExtractionUtils
import scala.tools.apiSearch.settings.Settings
import scala.util.Try
import org.scalatest.FlatSpec
import scala.tools.apiSearch.searchEngine.NameNotFound
import scala.tools.apiSearch.searchEngine.NameAmbiguous
import scala.tools.apiSearch.searchEngine.UnexpectedNumberOfTypeArgs

class QueryAnalyzerSpecs extends FlatSpec with ExtractionUtils {

  // the namespace used for all analyzer tests
  // in order to make sure all required classes from scala stdlib are loaded
  // they are referenced in `loadTypes.TypeToBeLoaded`
  val env = """
    package p {
      class A
      class B extends A
      class C extends B
      class D extends B

      class Ambiguous

      class List[+T]
    }

    package q {
      class Ambiguous
    }

    package scala.pkg {
      class Int
    }

    package loadTypes {
      trait TypesToBeLoaded {
        val char: Char
        val float: Float
        val f1: Any => Any
        val f2: (Any, Any) => Any
        val f3: (Any, Any, Any) => Any
        val tuple1: Tuple1[Any]
        val tuple2: (Any, Any)
        val tuple3: (Any, Any, Any)
        val list: List[Any]
      }
    }
    """

  "the query analyzer" should "resolve type names" in {
    val res = expectSuccess("A")

    res.fingerprint.mkString(" ") should include("p.A")
  }

  it should "treat unknown names of length 1 as type parameters and exclude them from fingerprints" in {
    val res = expectSuccess("x => y")

    res.fingerprint.mkString(" ") should (
      not include ("x") and
      not include ("y"))
  }

  it should "fail on unknown names" in {
    val res = expectFailure("Unknown")

    res should be(a[NameNotFound])
  }

  it should "return suggestions on ambiguous names" in {
    val res = expectFailure("Ambiguous")

    res should be(a[NameAmbiguous])
  }

  it should "fail on using names with incorrect number of arguments" in {
    val res = expectFailure("List[A, B]")

    res should be(a[UnexpectedNumberOfTypeArgs])
  }

  it should "succeed when using no type arguments" in {
    expectSuccess("List")
  }

  it should "prefer names from the Scala standard library over other namespaces" in {
    val res = expectSuccess("List[_]")

    res.fingerprint.mkString(" ") should (
      include("scala.collection.immutable.List") and
      not include ("p.List"))
  }

  it should "prefer names from the `scala` root namespace over names from subpackages of `scala`" in {
    val res = expectSuccess("Int")

    res.fingerprint.mkString(" ") should (
      include("scala.Int") and
      not include ("scala.pkg.Int"))
  }

  it should "correctly trace variance in nested type constructor applications" in {
    val res = expectSuccess("(A => B) => (C => D)")

    res.fingerprint should contain allOf ("+p.A_0", "-p.B_0", "-p.C_0", "+p.D_0")
  }

  it should "add increasing occurrence numbers to repeated elements" in {
    val res = expectSuccess("(A, A, A)")

    res.fingerprint should contain allOf ("+p.A_0", "+p.A_1", "+p.A_2")
  }

  it should "include sub classes of types at covariant positions" in {
    val res = expectSuccess("_ => A")

    res.fingerprint.mkString(" ") should (
      include("p.B") and
      include("p.C") and
      include("p.D"))
  }

  it should "use the bottom type as a sub class of every type" in {
    val res = expectSuccess("_ => A")

    res.fingerprint.mkString(" ") should (
      include("scala.Nothing"))
  }

  it should "include base classes of types at contravariant positions" in {
    val res = expectSuccess("C => _")

    res.fingerprint.mkString(" ") should (
      include("p.B") and
      include("p.A") and
      include("scala.Any"))
  }

  it should "yield a lower boost for types in deeper nested positions" in {
    val res = expectSuccess("(A, (B, _))")

    val A = res.types.find(_.typeName == "p.A").get
    val B = res.types.find(_.typeName == "p.B").get

    A.boost should be > (B.boost)
  }

  it should "yield a lower boost for types farther away from the original query type" in {
    val res = expectSuccess("A")

    val A = res.types.find(_.typeName == "p.A").get
    val B = res.types.find(_.typeName == "p.B").get

    A.boost should be > (B.boost)
  }

  it should "yield a boost of 1 for a single type" in {
    val res = expectSuccess("A")

    val A = res.types.find(_.typeName == "p.A").get

    A.boost should be(1f +- 0.01f)
  }

  it should "omit the outermost function application" in {
    val res = expectSuccess("A => B")

    res.fingerprint.mkString(" ") should not include ("Function1")
  }

  it should "normalize curried querries" in {
    val res1 = expectSuccess("A => B => C")
    val res2 = expectSuccess("(A, B) => C")

    res1 should equal(res2)

    val res3 = expectSuccess("A => (B, C) => D")
    val res4 = expectSuccess("(A, B, C) => D")

    res3 should equal(res4)
  }

  it should "not omit inner function types" in {
    val res = expectSuccess("(A => B) => C")

    res.fingerprint should contain("-scala.Function1_0")
  }

  def expectSuccess(s: String) = {
    val res = analyzer(QueryParser(s).getOrElse(???)).get
    res should be('right)
    res.getOrElse(???)
  }

  def expectFailure(s: String) = {
    val res = analyzer(QueryParser(s).getOrElse(???)).get
    res should be('left)
    res.swap.getOrElse(???)
  }

  val analyzer = {
    val classEntities = extractAllClasses(env)

    def toMultiMap[K, V](ps: Seq[(K, V)]): Map[K, Try[List[V]]] = ps
      .distinct
      .foldLeft(Map[K, List[V]]()) { (acc, keyAndValue) =>
        val values = acc.getOrElse(keyAndValue._1, Nil)
        acc + (keyAndValue._1 -> (keyAndValue._2 :: values))
      }
      .mapValues(sub => Try(sub))
      .withDefaultValue(Try(Nil))

    val findClassesBySuffix = toMultiMap(for {
      cls <- classEntities
      suffix <- cls.name.split("\\.").toList.tails.filterNot(_ == Nil).map(_.mkString("."))
    } yield (suffix, cls)) andThen (SearchEngine.favorScalaStdLib _)

    val findSubClasses = toMultiMap(for {
      cls <- classEntities
      base <- cls.baseTypes
      baseCls <- classEntities.filter(_.name == base.name)
    } yield (baseCls, cls))

    new QueryAnalyzer(Settings.fromApplicationConf.query, findClassesBySuffix, findSubClasses)
  }
}
