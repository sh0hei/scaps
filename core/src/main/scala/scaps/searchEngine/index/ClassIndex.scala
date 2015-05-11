package scaps.searchEngine.index

import scala.util.Try

import org.apache.lucene.analysis.core.KeywordAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.MatchAllDocsQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.Directory

import scaps.settings.Settings
import scaps.webapi.ClassEntity
import scaps.webapi.Covariant
import scaps.webapi.Module
import scaps.webapi.TypeEntity

/**
 * Persists class entities and provides lookup for classes by name.
 *
 * This index is mainly used for fast access to class hierarchies for query building.
 */
class ClassIndex(val dir: Directory, settings: Settings) extends Index[ClassEntity] {
  import ClassIndex._

  val analyzer = new KeywordAnalyzer

  def addEntities(entities: Seq[ClassEntity]): Try[Unit] = Try {
    val distinctEntities = entities.distinct
    val indexedClasses = allClasses().get

    val entitiesWithModules = distinctEntities.map { cls =>
      indexedClasses.find(_.name == cls.name)
        .fold(cls) { indexedCls =>
          cls.copy(referencedFrom = cls.referencedFrom ++ indexedCls.referencedFrom)
        }
    }

    withWriter { writer =>
      entitiesWithModules.foreach { entity =>
        val doc = toDocument(entity)
        writer.updateDocument(new Term(fields.name, entity.name), doc)
      }
    }.get
  }

  def deleteEntitiesIn(module: Module): Try[Unit] = Try {
    val q = new TermQuery(new Term(fields.modules, module.moduleId))
    val classesInModule = search(q).get

    withWriter { writer =>
      classesInModule.foreach { cls =>
        val clsTerm = new Term(fields.name, cls.name)

        val clsWithoutModule = cls.copy(referencedFrom = cls.referencedFrom - module)

        if (clsWithoutModule.referencedFrom.isEmpty) {
          writer.deleteDocuments(clsTerm)
        } else {
          writer.updateDocument(clsTerm, toDocument(clsWithoutModule))
        }
      }
    }.get
  }

  /**
   * Searches for class entities whose last parts of the full qualified name are `suffix`
   * and accept `noArgs` type parameters.
   */
  def findClassBySuffix(suffix: String, moduleIds: Set[String] = Set()): Try[Seq[ClassEntity]] = {
    val query = new BooleanQuery()
    query.add(new TermQuery(new Term(fields.suffix, suffix)), Occur.MUST)

    if (!moduleIds.isEmpty) {
      val moduleQuery = new BooleanQuery()

      for (moduleId <- moduleIds) {
        moduleQuery.add(new TermQuery(new Term(fields.modules, moduleId)), Occur.SHOULD)
      }

      query.add(moduleQuery, Occur.MUST)
    }

    search(query)
  }

  def findSubClasses(tpe: TypeEntity): Try[Seq[ClassEntity]] = {
    def partialTypes(tpe: TypeEntity): List[TypeEntity] = {
      def argPerms(args: List[TypeEntity]): List[List[TypeEntity]] = args match {
        case Nil => List(Nil)
        case a :: as =>
          for {
            aPerm <- TypeEntity("_", Covariant, Nil) :: partialTypes(a)
            asPerm <- argPerms(as)
          } yield aPerm :: asPerm
      }

      argPerms(tpe.args).map(perm => tpe.copy(args = perm))
    }

    val q = new BooleanQuery
    for (perm <- partialTypes(tpe).take(BooleanQuery.getMaxClauseCount)) {
      q.add(new TermQuery(new Term(fields.baseClass, perm.signature)), Occur.SHOULD)
    }

    search(q)
  }

  def allClasses(): Try[Seq[ClassEntity]] =
    search(new MatchAllDocsQuery)

  override def toDocument(entity: ClassEntity): Document = {
    val doc = new Document

    doc.add(new TextField(fields.name, entity.name, Field.Store.YES))

    for (suffix <- suffixes(entity.name)) {
      doc.add(new TextField(fields.suffix, suffix, Field.Store.NO))
    }

    for (baseClass <- entity.baseTypes) {
      val withWildcards = baseClass.renameTypeParams(entity.typeParameters, _ => "_")
      doc.add(new TextField(fields.baseClass, withWildcards.signature, Field.Store.YES))
    }

    doc.add(new StoredField(fields.entity, upickle.write(entity)))

    for (module <- entity.referencedFrom) {
      doc.add(new TextField(fields.modules, module.moduleId, Field.Store.YES))
    }

    doc
  }

  private def suffixes(name: String): List[String] = name match {
    case "" => Nil
    case s =>
      s.indexWhere(c => c == '.' || c == '#') match {
        case -1  => s :: Nil
        case idx => s :: suffixes(name.drop(idx + 1))
      }
  }

  override def toEntity(doc: Document): ClassEntity = {
    val json = doc.getValues(fields.entity)(0)

    upickle.read[ClassEntity](json)
  }
}

object ClassIndex {
  object fields {
    val name = "name"
    val suffix = "suffix"
    val baseClass = "baseClass"
    val entity = "entity"
    val modules = "modules"
  }
}
