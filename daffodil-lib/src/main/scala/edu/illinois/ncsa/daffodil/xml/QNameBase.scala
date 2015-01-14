package edu.illinois.ncsa.daffodil.xml

import edu.illinois.ncsa.daffodil.exceptions.Assert
import edu.illinois.ncsa.daffodil.exceptions.ThrowsSDE
import edu.illinois.ncsa.daffodil.xml._
import edu.illinois.ncsa.daffodil.equality._
import scala.language.reflectiveCalls
import edu.illinois.ncsa.daffodil.api.Diagnostic
import java.net.URISyntaxException

/**
 * Please centralize QName handling here.
 *
 * This can be XSD-specific, but should be generic capabilities.
 *
 * There are two concrete classes of QNames for named things: Global and Local
 *
 * There are two concrete classes of referencing QNames, for references to
 * global objects (as in ref="tns:bar" and type="xs:int") and for references
 * which can be to local objects as well as global, such as in DPath step expressions like
 * ../foo:bar/baz.
 *
 * There is a precedent for how xpath expressions work in XSD which is the xpath
 * expressions that are used in the xs:selector and xs:field sub-elements of the
 * xs:key and xs:unique constraints.
 *
 * Based on this, there are two kinds of named things: globally named things, and
 * locally. The only locally named things are local element declarations. Global things
 * include elements, types, groups, etc.
 *
 * These distinctions are necessary because of XSD's elementFormDefault concept.
 * This makes matching against a local name either equivalent to matching against
 * a global name (elementFormDefault="qualified"), or local name matching is
 * namespace independent (elementFormDefault="unqualified"), which is the default
 * behavior.
 *
 * The QName objects are always constructed with a prefix (if present), local
 * name part, and namespace.
 *
 * Consider this case:
 *
 * <xs:element ref="bar" xmlns="someURN".../>
 *
 * In that case, the name "bar" is resolved in the default namespace to be
 * {someURN}bar.
 *
 * However, in the absence of a default namespace binding, the name "bar" will
 * resolve to {No_Namespace}bar, and that will only have a counterpart if
 * there is a schema with no target namespace which defines bar as a global def
 * of some sort.
 *
 * Now consider this case:
 *
 * <xs:schema targetNamespace="someURN">
 *    ...
 * <xs:element name="foo" form="qualified">
 * <xs:complexType>
 * <xs:sequence>
 *      <xs:element name="bar" form="qualfied"..../>
 *
 * Now, in some other place we have a DPath such as
 *
 * <.... xmlns="someURN"
 *  <xs:selector xpath="../foo/bar"/>
 *
 * This does not match because despite the xmlns defining a default namespace,
 * that is not used when considering path steps. Both foo and bar are qualified
 * which means path steps referencing them MUST have prefixes on them.
 * (This is the behavior of Xerces as of 2014-09-29)
 *
 * If there is no default namespace at the point of reference, then
 * this will only match if
 * (1) There is no targetNamespace surrounding the decl of element bar. (Which ought
 *  to draw a warning given that there is no namespace but there is an explicit request
 *  for qualified names.)
 * (2) The schema's element form default is 'unqualified' (which is the
 * default). In this case the local name is the only thing that has to match.
 *
 * This latter case is represented by a StepRef with NoNamespace for the namespace,
 * matching against a LocalDeclQName, which will have NoNamespace by way of
 * the elementFormDefault being unqualified.
 *
 */

/*
 * Use this factory to create the right kinds of QNames.
 */
object QName extends QNameRegexMixin {

  def resolveRef(qnameString: String, scope: scala.xml.NamespaceBinding): Option[RefQName] =
    RefQNameFactory.resolveRef(qnameString, scope)

  private val NSFormat = """\{([^\{\}]*)\}(.+)""".r

  /**
   * Specialized getQName function for handling
   * manually specified variables via the CLI.
   *
   * Variables will be of the format:
   *
   * 1. {nsURI}varName=value
   * 2. {}varName=value       // explicitly means NoNamespace
   * 3. varName=value         // unspecified namespace, i.e., might default
   */
  def refQNameFromExtendedSyntax(extSyntax: String): Either[Throwable, RefQName] = {
    val res =
      try {
        extSyntax match {
          case NSFormat("", varName) => Right(RefQName(None, varName, NoNamespace))
          case NSFormat(uriString, varName) => Right(RefQName(None, varName, NS(uriString)))
          case NCNameRegex(local) => Right(RefQName(None, local, UnspecifiedNamespace))
          case _ => Left(new ExtendedQNameSyntaxException(Some(extSyntax), None))
        }
      } catch {
        case ex: URISyntaxException => Left(new ExtendedQNameSyntaxException(None, Some(ex)))
      }
    res
  }

  def resolveStep(qnameString: String, scope: scala.xml.NamespaceBinding): Option[StepQName] =
    StepQNameFactory.resolveRef(qnameString, scope)

  def createLocal(name: String, targetNamespace: NS, isQualified: Boolean,
    scope: scala.xml.NamespaceBinding) = {
    val ns = if (isQualified) targetNamespace else NoNamespace
    // TODO: where we parse xmlSchemaDocument, a check for
    // xs:schema with no target namespace, but elementFormDefault 'qualified'
    // should emit a warning. It is not ALWAYS incorrect, as a schema
    // designed for inclusion into another schema (via xs:include)
    // can take a position on how its local element names are to
    // be qualified without having a target namespace itself.
    //
    // But,... it is very likely to be an error.
    //
    LocalDeclQName(None, name, ns)
  }

  def createGlobal(name: String, targetNamespace: NS) = {
    GlobalQName(None, name, targetNamespace)
  }
}

protected sealed abstract class QNameSyntaxExceptionBase(kind: String, offendingSyntax: Option[String], cause: Option[Throwable])
  extends Exception(offendingSyntax.getOrElse(null), cause.getOrElse(null)) {

  override def getMessage = {
    val intro = "Invalid syntax for " + kind + " "
    val details = (offendingSyntax, cause) match {
      case (Some(syntax), Some(cause)) => "'%s'. Caused by: '%s'".format(syntax, cause)
      case (None, Some(cause)) => "'%s'".format(cause.getMessage())
      case (Some(syntax), None) => "'%s'.".format(syntax)
      case _ => Assert.usageError("supply either offendingSyntax, or cause or both")
    }
    intro + details
  }
}

class ExtendedQNameSyntaxException(offendingSyntax: Option[String], cause: Option[Throwable])
  extends QNameSyntaxExceptionBase("extended QName", offendingSyntax, cause)

class QNameSyntaxException(offendingSyntax: Option[String], cause: Option[Throwable])
  extends QNameSyntaxExceptionBase("QName", offendingSyntax, cause)

protected trait QNameBase {

  /**
   * The prefix is not generally involved in matching, but they
   * must show up in diagnostic messages. Mistakes by having the wrong prefix
   * or by omitting one, are very common.
   */
  def prefix: Option[String]
  def local: String
  def namespace: NS // No namespace is represented by the NoNamespace object.

  override def toString = toPrettyString

  /**
   * For purposes of hashCode and equals, we disregard the prefix
   */
  override lazy val hashCode = namespace.hashCode + local.hashCode

  override def equals(other: Any) = {
    val res = other match {
      case qn: QNameBase => (local =:= qn.local && namespace =:= qn.namespace)
      case _ => false
    }
    res
  }

  def toPrettyString: String = {
    (prefix, local, namespace) match {
      case (Some(pre), local, NoNamespace) => Assert.invariantFailed("QName has prefix, but NoNamespace")
      case (Some(pre), local, UnspecifiedNamespace) => Assert.invariantFailed("QName has prefix, but unspecified namespace")

      case (None, local, NoNamespace) => "{}" + local
      case (None, local, UnspecifiedNamespace) => local
      case (None, local, ns) => "{" + ns + "}" + local
      case (Some(pre), local, ns) => pre + ":" + local
    }
  }

  /**
   * expanded name looks like {...uri...}local.
   * The prefix isn't part of it.
   */
  //  def toExpandedName: String = {
  //    if (namespace.isNoNamespace) "{}" + local
  //    else if (namespace.isUnspecified) local
  //    else "{" + namespace.uri + "}" + local
  //  }

  def matches[Q <: QNameBase](other: Q): Boolean
}

/**
 * common base trait for named things, both global and local
 */
sealed trait NamedQName
  extends QNameBase {
  if (prefix.isDefined) {
    Assert.usage(!namespace.isNoNamespace)
    Assert.usage(!namespace.isUnspecified)
  }
}

/**
 * QName for a local declaration.
 */
final case class LocalDeclQName(prefix: Option[String], local: String, namespace: NS)
  extends NamedQName {

  override def matches[Q <: QNameBase](other: Q): Boolean = {
    other match {
      case StepQName(_, `local`, `namespace`) => {
        // exact match
        //
        // This means either this was unqualified so the namespace
        // arg is NoNamespace, and the local matched that, or...
        // it means this was qualified but the other matched that
        // (which can happen even without a prefix if there is a default
        // namespace i.e., xmlns="...")
        true
      }
      case StepQName(_, `local`, NoNamespace) => {
        //
        // This case matches if the other has no NS qualifier
        // but in this case, this local decl name DOES have
        // some other URI as its namespace, so there is
        // explicitly no match here.
        //
        false
      }
      case StepQName(_, `local`, _) => false // NS didn't match, even if local did we don't care.
      case StepQName(_, notLocal, _) => false
      case _ => Assert.usageError("other must be a StepQName")
    }
  }
}

/**
 * QName for a global declaration or definition (of element, type, group, format, etc.)
 */
final case class GlobalQName(prefix: Option[String], local: String, namespace: NS)
  extends NamedQName {

  override def matches[Q <: QNameBase](other: Q): Boolean = {
    other match {
      // StepQNames match against global names in the case of a path
      // step that refers to an element that is defined in its
      // group, via an element reference.
      case StepQName(_, `local`, `namespace`) => true // exact match
      case StepQName(_, _, _) => false
      // RefQNames match against global names in element references,
      // group references, type references (i.e., type="..."), etc.
      case RefQName(_, `local`, `namespace`) => true // exact match
      case RefQName(_, _, _) => false
      case _ => Assert.usageError("other must be a StepQName or RefQName")
    }
  }
}

/**
 * base trait for Qnames that reference other things
 */
protected sealed trait RefQNameBase extends QNameBase

/**
 * A qname as found in a ref="foo:bar" attribute, or a type="foo:barType" attribute.
 * Or a variable reference e.g., \$foo:barVar in an expression.
 *
 * These are references to globally defined things.
 */
final case class RefQName(prefix: Option[String], local: String, namespace: NS)
  extends RefQNameBase {

  override def matches[Q <: QNameBase](other: Q): Boolean = {
    other match {
      case named: GlobalQName => named.matches(this)
      case _ => Assert.usageError("other must be a GlobalQName")
    }
  }

  def toGlobalQName = QName.createGlobal(this.local, this.namespace)
}

/**
 * A qname as found in a path step as in ../foo:bar/baz/quux
 *
 * Differs from RefQName in that it has to match up to LocalDeclQNames
 * properly.
 */
final case class StepQName(prefix: Option[String], local: String, namespace: NS)
  extends RefQNameBase {

  override def matches[Q <: QNameBase](other: Q): Boolean = {
    other match {
      case named: NamedQName => named.matches(this) // let the named things decide how other things match them.
      case _ => Assert.usageError("other must be a NamedQName")
    }
  }

  /**
   * Finds a match in a list of things that have QNames.
   * Used for finding if a named path step has corresponding element declaration.
   *
   * Handles local or global matches
   *
   */
  def findMatch[T <: { def namedQName: NamedQName }](candidates: Seq[T]): Option[T] = {
    val matched = candidates.find { x =>
      val other = x.namedQName
      val res = matches(other)
      res
    }
    matched
  }

}

protected trait RefQNameFactoryBase[T] extends QNameRegexMixin {

  protected def resolveDefaultNamespace(scope: scala.xml.NamespaceBinding): Option[String]

  protected def constructor(prefix: Option[String], local: String, namespace: NS): T

  def resolveRef(qnameString: String, scope: scala.xml.NamespaceBinding): Option[T] = {
    val (prefix, local) = qnameString match {
      case QNameRegex(prefix, local) => (Option(prefix), local)
      case _ => throw new QNameSyntaxException(Some(qnameString), None)
    }
    // note that the prefix, if defined, can never be ""
    val optURI = prefix match {
      case None => resolveDefaultNamespace(scope)
      case Some(pre) => Option(scope.getURI(pre))
    }
    val optNS = (prefix, optURI) match {
      case (None, None) => Some(NoNamespace)
      case (Some(pre), None) => None // Error. Unresolvable Prefix
      case (_, Some(ns)) => Some(NS(ns))
    }
    val res = optNS map { constructor(prefix, local, _) }
    res
  }
}

object RefQNameFactory extends RefQNameFactoryBase[RefQName] {

  override def constructor(prefix: Option[String], local: String, namespace: NS) =
    RefQName(prefix, local, namespace)

  override def resolveDefaultNamespace(scope: scala.xml.NamespaceBinding) =
    Option(scope.getURI(null)) // could be a default namespace
}

object StepQNameFactory extends RefQNameFactoryBase[StepQName] {

  override def constructor(prefix: Option[String], local: String, namespace: NS) =
    StepQName(prefix, local, namespace)

  override def resolveDefaultNamespace(scope: scala.xml.NamespaceBinding) =
    None // don't consider default namespace
}

trait QNameRegexMixin {
  private val xC0_D6 = ("""[\x{C0}-\x{D6}]""")
  private val xD8_F6 = """[\x{D8}-\x{F6}]"""
  private val xF8_2FF = """[\x{F8}-\x{2FF}]"""
  private val x370_37D = """[\x{370}-\x{37D}]"""
  private val x37F_1FFF = """[\x{37F}-\x{1FFF}]"""
  private val x200C_200D = """\x{200c}|\x{200d}"""
  private val x2070_218F = """[\x{2070}-\x{218F}]"""
  private val x2C00_2FEF = """[\x{2C00}-\x{2FEF}]"""
  private val x3001_D7FF = """[\x{3001}-\x{D7FF}]"""
  private val xF900_FDCF = """[\x{F900}-\x{FDCF}]"""
  private val xFDF0_FFFD = """[\x{FDF0}-\x{FFFD}]"""
  private val x10000_EFFFF = """[\x{10000}-\x{EFFFF}]"""
  private val range0_9 = """[0-9]"""
  private val xB7 = """\xB7"""
  private val x0300_036F = """[\x{0300}-\x{036F}]"""
  private val x203F_2040 = """[\x{203F}-\x{2040}]"""

  private val ncNameStartChar = """[A-Z]|_|[a-z]""" + "|" + xC0_D6 + "|" + xD8_F6 + "|" + xF8_2FF + "|" + x37F_1FFF + "|" + x200C_200D + "|" +
    x2070_218F + "|" + x2C00_2FEF + "|" + x3001_D7FF + "|" + xF900_FDCF + "|" + xFDF0_FFFD + "|" + x10000_EFFFF
  private val ncNameChar = ncNameStartChar + "|" + "\\-" + "|" + "\\." + "|" + range0_9 // + "|" + xB7 + "|" + x0300_036F + "|" + x203F_2040
  private val NCNameRegexString = "((?:" + ncNameStartChar + ")(?:(?:" + ncNameChar + ")*))"
  val NCNameRegex = NCNameRegexString.r
  val QNameRegex = ("(?:" + NCNameRegexString + "\\:)?" + NCNameRegexString).r
}