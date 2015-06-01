package scaps.searchEngine

import org.scalatest.Matchers
import org.scalatest.FlatSpec
import scaps.featureExtraction.ExtractionUtils
import scaps.webapi.Covariant
import scaps.webapi.TypeEntity
import scaps.webapi.Variance

class TypeFingerprintSpecs extends FlatSpec with Matchers with ExtractionUtils {
  "a type fingerprint" should "contain a term's type, variance and occurrence number" in {
    extractTerms("""
      package p

      object O {
        val i = 1
      }
      """)(
      ("p.O.i", Fingerprint(_) should be(fingerprint((Covariant, TypeEntity.Int.name, 0)))))
  }

  it should "increse occurrence numbers on repeated types" in {
    extractTerms("""
      package p

      object O {
        val i = (1, 2, 3)
      }
      """)(
      ("p.O.i", Fingerprint(_).toString should (
        include("+scala.Int_0") and
        include("+scala.Int_1") and
        include("+scala.Int_2"))))
  }

  it should "normalize member access" in {
    val ts = extractAllTerms("""
      package p

      class C {
        val i = 1
      }

      object O {
        def m(c: C) = 1
        val f = (c: C) => 1
      }
      """)

    val i = ts.find(_.name == "p.C#i").get
    val m = ts.find(_.name == "p.O.m").get
    val f = ts.find(_.name == "p.O.f").get

    Fingerprint(i) should equal(Fingerprint(m))
    Fingerprint(i) should equal(Fingerprint(f))
  }

  it should "normalize method invocations" in {
    val ts = extractAllTerms("""
      package p

      class C {
        def m1(i: Int) = 1
      }

      object O {
        def m2(c: C, i: Int) = 1
        def m3(c: C)(i: Int) = 1
      }
      """)

    val m1 = ts.find(_.name == "p.C#m1").get
    val m2 = ts.find(_.name == "p.O.m2").get
    val m3 = ts.find(_.name == "p.O.m3").get

    Fingerprint(m1) should equal(Fingerprint(m2))
    Fingerprint(m1) should equal(Fingerprint(m3))
  }

  it should "handle variance correctly" in {
    extractTerms("""
      package p

      trait T[-A, +B]

      object O {
        val v: T[T[Int, Char], T[Float, String]] = ???
        def m(x: T[Int, Char]): T[Float, String] = ???
      }

      class C {
        def m(x: T[Int, Char]): T[Float, String] = ???
      }
      """)(
      ("p.O.v", Fingerprint(_).toString should (
        include("+scala.Int_0") and
        include("-scala.Char_0") and
        include("-scala.Float_0") and
        include("+java.lang.String_0"))),
      ("p.O.m", Fingerprint(_).toString should (
        include("+scala.Int_0") and
        include("-scala.Char_0") and
        include("-scala.Float_0") and
        include("+java.lang.String_0"))),
      ("p.C#m", Fingerprint(_).toString should (
        include("-p.C_0") and
        include("+scala.Int_0") and
        include("-scala.Char_0") and
        include("-scala.Float_0") and
        include("+java.lang.String_0"))))
  }

  it should "include type arguments" in {
    extractTerms("""
      package p

      object O {
        val is = List(1)
      }
      """)(
      ("p.O.is", Fingerprint(_).toString should (
        include("+scala.collection.immutable.List_0") and
        include("+scala.Int_0"))))
  }

  it should "use upper type parameter bounds at contravariant positions" in {
    extractTerms("""
      package p

      trait T {
        def m[A <: scala.AnyVal](x: A): Unit
      }
      """)(
      ("p.T#m", Fingerprint(_).toString should (
        include("-scala.AnyVal_0") and
        not include ("-A_0"))))
  }

  it should "use lower type parameter bounds at covariant positions" in {
    extractTerms("""
      package p

      trait T {
        def m[A >: scala.AnyVal]: A
      }
      """)(
      ("p.T#m", Fingerprint(_).toString should (
        include("+scala.AnyVal_0") and
        not include ("+A_0"))))
  }

  it should "use top type for unbound type parameters at contravariant positions" in {
    extractTerms("""
      package p

      trait T {
        def m[A](x: A): Unit
      }
      """)(
      ("p.T#m", Fingerprint(_).toString should (
        include("-scala.Any_0"))))
  }

  it should "use bottom type for unbound type parameters at covariant positions" in {
    extractTerms("""
      package p

      trait T {
        def m[A]: A
      }
      """)(
      ("p.T#m", Fingerprint(_).toString should (
        include("+scala.Nothing_0"))))
  }

  it should "support higher kinded type parameters" in {
    extractTerms("""
      package p

      trait Tr[A]

      object O {
        def m[M[X] <: Tr[X]](x: M[Int]): M[String] = ???
      }
      """)(
      ("p.O.m", Fingerprint(_).toString should (
        include("-p.Tr_0")
        and include("+scala.Nothing_0")
        and include("java.lang.String_0"))))
  }

  it should "use variance of bounds of higher kinded type parameters" in {
    extractTerms("""
      package p

      trait Tr[+A]

      object O {
        def m[M[X] <: Tr[X]](x: M[Int]): M[Float] = ???
      }
      """)(
      ("p.O.m", Fingerprint(_).toString should (
        include("-scala.Int_0")
        and include("+scala.Float_0"))))
  }

  it should "substitute all type parameters" in {
    extractTerms("""
      package p

      trait Tr[X] {}

      object O {
        def m[Y, M[X] <: Tr[X]](x: M[Y]): M[Y] = ???
      }
      """)(
      ("p.O.m", Fingerprint(_).toString should (
        not include ("Y"))))
  }

  it should "handle variance correctly when using type params" in {
    extractTerms("""
      package p

      class Cl[+A] {
        def m[B <: AnyVal, C >: Int](x: Cl[B]): Cl[C]
      }
      """)(
      ("p.Cl#m", Fingerprint(_).toString should (
        include("-scala.Any_0") and
        include("-scala.AnyVal_0") and
        include("+scala.Int_0"))))
  }

  it should "treat objects implementing a single trait as an instance of this trait" in {
    extractTerms("""
      package p

      trait T

      object O extends T
      """)(
      ("p.O", Fingerprint(_) should be(fingerprint((Covariant, "p.T", 0)))))
  }

  it should "ignore byName types" in {
    extractTerms("""
      package p

      object O {
        def m(i: => Int) = 1
      }
      """)(
      ("p.O.m", Fingerprint(_).toString() should (
        not include (TypeEntity.ByName.name) and
        include("-scala.Int_0"))))
  }

  it should "ignore repeated types" in {
    extractTerms("""
      package p

      object O {
        def m(i: Int*) = 1
      }
      """)(
      ("p.O.m", Fingerprint(_).toString() should (
        not include (TypeEntity.Repeated.name) and
        include("-scala.Int_0"))))
  }

  it should "preserve function arguments in higher kinded functions" in {
    extractTerms("""
      package p

      object O {
        def m(f: Int => String): String = f(1)
      }
      """)(
      ("p.O.m", Fingerprint(_).toString() should (
        include(TypeEntity.Function.name(1)))))
  }

  def fingerprint(types: (Variance, String, Int)*) =
    Fingerprint(types.toList.map { case (v, tpe, depth) => Fingerprint.Type(v, tpe, depth) })
}