package scaps.featureExtraction

import scaps.webapi._
import scala.tools.nsc.interactive.Global

trait EntityFactory {
  val compiler: Global

  import compiler._

  def extractEntities(classSym: Symbol, getDocComment: (Symbol, Symbol) => String): List[Entity] =
    if (isClassOfInterest(classSym)) {
      val cls = createClassEntity(classSym)

      val objTerm =
        if (isTermOfInterest(classSym)) Some(createTermEntity(classSym, getDocComment(classSym, classSym)))
        else None

      val memberSymsWithComments = classSym.tpe.members
        .filter(isTermOfInterest)
        .map { m =>
          val copy = m.cloneSymbol(classSym)
          copy.info = classSym.tpe.memberInfo(m)
          (copy, getDocComment(m, classSym))
        }

      val referencedClasses = memberSymsWithComments.flatMap {
        case (sym, _) =>
          sym.tpe.collect { case t => t.typeSymbol }
            .filter(isClassOfInterest _)
            .map(createClassEntity _)
      }.toList

      val members = memberSymsWithComments
        .map((createTermEntity _).tupled)
        .toList

      cls :: objTerm.toList ::: members ::: referencedClasses
    } else {
      Nil
    }

  def createClassEntity(sym: Symbol): ClassEntity = {
    val baseTypes = sym.tpe.baseTypeSeq.toList.tail
      .filter(tpe => isClassOfInterest(tpe.typeSymbol))
      .map(tpe => createTypeEntity(tpe, Covariant))
    ClassEntity(qualifiedName(sym, true), typeParamsFromOwningTemplates(sym), baseTypes)
  }

  def isClassOfInterest(sym: Symbol): Boolean =
    (sym.isClass || sym.isModuleOrModuleClass) &&
      !sym.isAnonOrRefinementClass &&
      !sym.isLocalClass &&
      sym.isPublic

  def createTermEntity(sym: Symbol, rawComment: String): TermEntity = {
    val (typeParams, tpe) = createTypeEntity(sym)
    TermEntity(qualifiedName(sym, false), typeParams, tpe, rawComment)
  }

  def isTermOfInterest(sym: Symbol): Boolean =
    (sym.isTerm || (sym.isConstructor && !sym.owner.isAbstractClass)) &&
      sym.isPublic

  def createTypeEntity(sym: Symbol): (List[TypeParameterEntity], TypeEntity) = {
    val (params, memberType) =
      if (sym.isMethod)
        methodType(sym)
      else if (sym.isModule)
        moduleType(sym)
      else
        (Nil, createTypeEntity(sym.tpe, Covariant))

    if (sym.owner.isClass && !sym.owner.isModuleClass && !sym.isConstructor) {
      val ownerParams = typeParamsFromOwningTemplates(sym)
      val ownerArgs = ownerParams.map(p => TypeEntity(p.name, Contravariant * p.variance, Nil))
      (ownerParams ++ params,
        TypeEntity.MemberAccess(TypeEntity(qualifiedName(sym.owner, true), Contravariant, ownerArgs), memberType))
    } else {
      (params, memberType)
    }
  }

  private def typeParamsFromOwningTemplates(sym: Symbol): List[TypeParameterEntity] = {
    sym.ownerChain.reverse.flatMap { owner =>
      owner.tpe.typeArgs.map(arg => createTypeParamEntity(arg.typeSymbol))
    }
  }

  def qualifiedName(sym: Symbol, isTypeName: Boolean): String = {
    def toName(sym: Symbol) =
      if (sym.isPackageObject)
        ""
      else
        sym.name.decode

    def rec(member: Symbol): String = {
      val owner = member.owner

      if (owner.isRootSymbol || owner == owner.owner)
        ""
      else if (owner.isPackageObject)
        rec(owner)
      else if (owner.hasPackageFlag || owner.hasModuleFlag || member.isConstructor)
        rec(owner) + toName(owner) + "."
      else
        rec(owner) + toName(owner) + "#"
    }

    val name =
      if (sym.isTypeParameter)
        toName(sym)
      else
        rec(sym) + toName(sym)

    if (isTypeName)
      s"$name${sym.moduleSuffix}"
    else
      name
  }

  private def createTypeEntity(tpe: Type, variance: Variance): TypeEntity = {
    def getVariance(idx: Int) = {
      val nscVariance =
        if (tpe.typeSymbol.isTypeParameter && tpe.bounds.hi.typeSymbol.typeParams.isDefinedAt(idx))
          tpe.bounds.hi.typeSymbol.typeParams(idx).variance
        else
          tpe.typeSymbol.typeParams(idx).variance
      if (nscVariance.isPositive)
        variance
      else if (nscVariance.isContravariant)
        variance.flip
      else
        Invariant
    }

    val args = tpe.typeArgs.zipWithIndex.map {
      case (arg, idx) => createTypeEntity(arg, getVariance(idx))
    }
    TypeEntity(qualifiedName(tpe.typeSymbol, true), variance, args)
  }

  private def methodType(sym: Symbol): (List[TypeParameterEntity], TypeEntity) = {
    val typeParams = sym.tpe.typeParams.map(createTypeParamEntity)

    def rec(paramss: List[List[Symbol]], resultTpe: Type): TypeEntity = paramss match {
      case Nil => createTypeEntity(resultTpe, Covariant)
      case params :: rest =>
        val paramTypes = params.map(p => createTypeEntity(p.tpe, Contravariant))
        val resultType = rec(rest, resultTpe.resultType)
        TypeEntity.MethodInvocation(paramTypes, resultType)
    }

    (typeParams, rec(sym.paramss, sym.tpe.resultType))
  }

  private def moduleType(sym: Symbol): (List[TypeParameterEntity], TypeEntity) = {
    val args = sym.tpe.parents.map { parent =>
      createTypeEntity(parent, Covariant)
    }

    (Nil, TypeEntity.Refinement(args))
  }

  private def createTypeParamEntity(typeSym: Symbol) =
    TypeParameterEntity(
      qualifiedName(typeSym, true),
      if (typeSym.variance.isCovariant)
        Covariant
      else if (typeSym.variance.isContravariant)
        Contravariant
      else Invariant,
      qualifiedName(typeSym.tpe.bounds.lo.typeSymbol, true),
      qualifiedName(typeSym.tpe.bounds.hi.typeSymbol, true))
}