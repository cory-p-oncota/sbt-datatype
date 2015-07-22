package sbt.datatype

abstract class CodeGenerator {

  def augmentIndentTrigger(s: String): Boolean
  def reduceIndentTrigger(s: String): Boolean

  class IndentationAwareBuffer(val indent: String, private var level: Int = 0) {
    private val buffer: StringBuilder = new StringBuilder
    def +=(opt: Option[String]): Unit = opt foreach +=
    def +=(it: Iterator[String]): Unit = it foreach +=
    def +=(s: String): Unit = {
      val clean: String = s.trim
      if (reduceIndentTrigger(clean)) level = 0 max (level - 1)
      buffer append (indent * level + clean + "\n")
      if (augmentIndentTrigger(clean)) level += 1
    }

    override def toString(): String = buffer.mkString
  }

  def generate(s: Schema): Map[String, String]
  final def generate(d: Definition, parent: Option[Protocol], superFields: List[Field]): Map[String, String] =
    d match {
      case p: Protocol    => generate(p, parent, superFields)
      case r: Record      => generate(r, parent, superFields)
      case e: Enumeration => generate(e)
    }
  def generate(p: Protocol, parent: Option[Protocol], superFields: List[Field]): Map[String, String]
  def generate(r: Record, parent: Option[Protocol], superFields: List[Field]): Map[String, String]
  def generate(e: Enumeration): Map[String, String]

  def genDoc(doc: Option[String]): Option[String]

}


class JavaCodeGen extends CodeGenerator {

  private def buffered(op: IndentationAwareBuffer => Unit): String = {
    val buffer = new IndentationAwareBuffer("\t")
    op(buffer)
    buffer.toString
  }

  override def augmentIndentTrigger(s: String) = s endsWith "{"
  override def reduceIndentTrigger(s: String) = s startsWith "}"

  override def generate(s: Schema): Map[String, String] =
    s.definitions flatMap (generate(_, None, Nil)) map {
      case (k, v) =>
        (k, buffered { b =>
          b += s"package ${s.namespace};"
          b += v.lines
        })

    } toMap

  override def generate(p: Protocol, parent: Option[Protocol], superFields: List[Field]): Map[String, String] = {
    val Protocol(name, doc, fields, children) = p
    val extendsCode = parent map (p => s"extends ${p.name}") getOrElse ""

    val code =
      buffered { b =>
        b += genDoc(doc)
        b += s"public abstract class $name $extendsCode {"
        b += s"    ${genFields(fields)}"
        b += s"    ${genConstructors(p, parent, superFields)}"
        b += s"    ${genAccessors(fields)}"
        b += s"    ${genEquals(p, superFields)}"
        b += s"    ${genHashCode(p, superFields)}"
        b += s"    ${genToString(p, superFields)}"
        b += s"}"
      }

    Map(genFileName(p) -> code) ++ (children flatMap (generate(_, Some(p), superFields ++ fields)))
  }

  override def generate(r: Record, parent: Option[Protocol], superFields: List[Field]): Map[String, String] = {
    val Record(name, doc, fields) = r
    val extendsCode = parent map (p => s"extends ${p.name}") getOrElse ""

    val code =
      buffered { b =>
        b += genDoc(doc)
        b += s"public final class $name $extendsCode {"
        b += s"    ${genFields(fields)}"
        b += s"    ${genConstructors(r, parent, superFields)}"
        b += s"    ${genAccessors(fields)}"
        b += s"    ${genEquals(r, superFields)}"
        b += s"    ${genHashCode(r, superFields)}"
        b += s"    ${genToString(r, superFields)}"
        b += s"}"
      }

    Map(genFileName(r) -> code)
  }

  override def generate(e: Enumeration): Map[String, String] = {
    val Enumeration(name, doc, values) = e

    val valuesCode = values.map{ case EnumerationValue(name, doc) =>
      (genDoc(doc) map (_ + "\n") getOrElse "") + name
    }.mkString("", ",\n", ";")

    val code =
      buffered { b =>
        b += genDoc(doc)
        b += s"public enum $name {"
        b +=     valuesCode.lines
        b += s"}"
      }

    Map(genFileName(e) -> code)
  }

  override def genDoc(doc: Option[String]): Option[String] =
    doc map (d => s"/** $d */")

  private def genFileName(d: Definition) = d.name + ".java"
  private def genFields(fields: List[Field]) = fields map genField mkString "\n"
  private def genField(f: Field) =
    buffered { b =>
      b += genDoc(f.doc)
      b += s"private ${genRealTpe(f.tpe)} ${f.name};"
    }
  private def genRealTpe(tpe: TpeRef): String = tpe match {
    case TpeRef(name, true, true)   => s"Lazy<$name[]>"
    case TpeRef(name, true, false)  => s"Lazy<$name>"
    case TpeRef(name, false, true)  => s"$name[]"
    case TpeRef(name, false, false) => name
  }
  private def genAccessors(fields: List[Field]) = fields map genAccessor mkString "\n"
  private def genAccessor(field: Field) =
    buffered { b =>
      val accessCode =
        if (field.tpe.lzy) s"this.${field.name}.get();"
        else s"this.${field.name};"
      val tpeSig =
        if (field.tpe.repeated) s"${field.tpe.name}[]"
        else field.tpe.name
      b += s"public $tpeSig ${field.name}() {"
      b += s"    return $accessCode"
      b += s"}"
    }

  private def genConstructors(cl: ClassLike, parent: Option[Protocol], superFields: List[Field]) = {
    val allFields = superFields ++ cl.fields
    val versionNumbers = allFields.map(_.since).sorted.distinct

    buffered { b =>
      versionNumbers foreach { v =>
        val (provided, byDefault) = allFields partition (_.since <= v)
        val ctorArguments = provided map (f => s"${genRealTpe(f.tpe)} _${f.name}") mkString ", "
        val superFieldsValues = superFields map {
          case f if provided contains f  => s"_${f.name}"
          case f if byDefault contains f => f.default getOrElse sys.error(s"Need a default value for field ${f.name}.")
        }
        val superCall = superFieldsValues.mkString("super(", ", ", ");")
        val assignments = cl.fields map {
          case f if provided contains f  => s"${f.name} = _${f.name};"
          case f if byDefault contains f => f.default map (d => s"${f.name} = $d;") getOrElse sys.error(s"Need a default value for field ${f.name}.")
        }
        b += s"public ${cl.name}($ctorArguments) {"
        b += s"    $superCall"
        b +=       assignments.toIterator
        b += s"}"
      }
    }
  }

  private def genEquals(cl: ClassLike, superFields: List[Field]) = {
    val allFields = superFields ++ cl.fields
    val body =
      if (allFields exists (_.tpe.lzy)) {
        "return this == obj; // We have lazy members, so use object identity to avoid circularity."
      } else {
        val comparisonCode =
          if (allFields.isEmpty) "return true;"
          else
            allFields.map {
              case f if f.tpe.repeated => s"java.util.Arrays.deepEquals(${f.name}(), o.${f.name}())"
              case f                   => s"${f.name}().equals(o.${f.name}())"
            }.mkString("return ", " && ", ";")

        buffered { b =>
          b +=  "if (this == obj) {"
          b +=  "    return true;"
          b += s"} else if (!(obj instanceof ${cl.name})) {"
          b +=  "    return false;"
          b +=  "} else {"
          b += s"    ${cl.name} o = (${cl.name})obj;"
          b += s"    $comparisonCode"
          b +=  "}"
        }
      }

    buffered { b =>
      b +=  "public boolean equals(Object obj) {"
      b += s"    $body"
      b +=  "}"
    }
  }

  private def genHashCode(cl: ClassLike, superFields: List[Field]) = {
    val allFields = superFields ++ cl.fields
    val body =
      if (allFields exists (_.tpe.lzy)) {
        "return super.hashCode();"
      } else {
        val computation = (allFields foldLeft ("17")) { (acc, f) => s"37 * ($acc + ${f.name}().hashCode())" }
        s"return $computation;"
      }

    buffered { b =>
      b +=  "public int hashCode() {"
      b += s"    $body"
      b +=  "}"
    }
  }

  private def genToString(cl: ClassLike, superFields: List[Field]) = {
    val allFields = superFields ++ cl.fields
    val code =
      allFields.map{ f =>
        s""" + "${f.name}: " + ${f.name}()"""
      }.mkString(s""" "${cl.name}(" """, " + \", \"", " + \")\"")

    buffered { b =>
      b +=  "public String toString() {"
      b += s"    return $code;"
      b +=  "}"
    }
  }

}

object CodeGen {
  def generate(ps: ProtocolSchema): String =
    {
      val ns = ps.namespace
      val types = ps.types map { tpe: TypeDef => generateType(ns, tpe) }
      val typesCode = types.mkString("\n")
      s"""package $ns

$typesCode"""
    }

  def generateType(namespace: String, td: TypeDef): String =
    {
      val name = td.name
      val fields = td.fields map { field: FieldSchema => s"""${field.name}: ${field.`type`.name}""" }
      val fieldsCode = fields.mkString(",\n  ")
      val ctorFields = td.fields map { field: FieldSchema => s"""val ${field.name}: ${field.`type`.name}""" }
      val ctorFieldsCode = ctorFields.mkString(",\n  ")
      val fieldNames = td.fields map { field: FieldSchema => field.name }
      val sinces = (td.fields map {_.since}).distinct.sorted
      val inclusives = sinces.zipWithIndex map { case (k, idx) =>
        val dropNum = sinces.size - 1 - idx
        sinces dropRight dropNum
      }
      val alternatives = inclusives dropRight 1
      val altCode = (alternatives map { alts =>
        generateAltCtor(td.fields, alts)
      }).mkString("\n    ")

      val fieldNamesCode = fieldNames.mkString(", ")
      val mainApply =
        s"""def apply($fieldsCode): $name =
    new $name($fieldNamesCode)"""
      s"""final class $name($ctorFieldsCode) {
  ${altCode}
  ${generateEquals(name, fieldNames)}
  ${generateHashCode(fieldNames)}
  ${generateCopy(name, td.fields)}
}

object $name {
  $mainApply
}"""
    }
  
  def generateAltCtor(fields: Vector[FieldSchema], versions: Vector[VersionNumber]): String =
    {
      val vs = versions.toSet
      val params = fields filter { f => vs contains f.since } map { f => s"${f.name}: ${f.`type`.name}" }
      val paramsCode = params.mkString(", ")
      val args = fields map { f =>
        if (vs contains f.since) f.name
        else quote(f.defaultValue getOrElse { sys.error(s"${f.name} is missing `default` value") },
          f.`type`.name)
      }
      val argsCode = args.mkString(", ")
      s"def this($paramsCode) = this($argsCode)"
    }
  def quote(value: String, tpe: String): String =
    tpe match {
      case "String" => s""""$value"""" // "
      case _        => value
    }

  def generateCopy(name: String, fields: Vector[FieldSchema]): String =
    {
      val params = fields map { f => s"${f.name}: ${f.`type`.name} = this.${f.name}" }
      val paramsCode = params.mkString(",\n    ")
      val args = fields map { f => f.name }
      val argsCode = args.mkString(", ")
      s"private[this] def copy($paramsCode): $name =\n" +
      s"    new $name($argsCode)"
    }

  def generateEquals(name: String, fieldNames: Vector[String]): String =
    {
      val fieldNamesEq = fieldNames map { n: String => s"(this.$n == x.$n)" }
      val fieldNamesEqCode = fieldNamesEq.mkString(" &&\n        ")
      s"""override def equals(o: Any): Boolean =
    o match {
      case x: $name =>
        $fieldNamesEqCode
      case _ => false
    }"""
    }

  def generateHashCode(fieldNames: Vector[String]): String =
    {
      val fieldNamesHash = fieldNames map { n: String => 
        s"hash = hash * 31 + this.$n.##"
      }
      val fieldNameHashCode = fieldNamesHash.mkString("\n      ") 
      s"""override def hashCode: Int =
    {
      var hash = 1
      $fieldNameHashCode
      hash
    }"""
    }
}

