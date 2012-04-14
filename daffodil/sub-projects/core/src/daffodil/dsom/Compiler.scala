package daffodil.dsom

import java.io._

import scala.xml.Node
import scala.xml.XML

import daffodil.api.DFDL
import daffodil.exceptions._
import daffodil.util.Validator
import daffodil.xml.XMLUtils
import daffodil.grammar._
import daffodil.processors._
import daffodil.util.Misc._

class Compiler extends DFDL.Compiler {
  var root: String = ""
  var rootNamespace: String = ""
  var debugMode = false


  def setDistinguishedRootNode(name: String, namespace: String = ""): Unit = {
    root = name
    rootNamespace = namespace
  }

  def setExternalDFDLVariable(name: String, namespace: String, value: String): Unit = {
    Assert.notYetImplemented()
  }

  def setDebugging(flag: Boolean) {
    debugMode = flag
  }

  /*
   * for unit testing of front end
   */
  private[dsom] def frontEnd(xml: Node) = {
    Compiler.validateDFDLSchema(xml)
    val sset = new SchemaSet(List(xml))
    //
    // let's make sure every element declaration compiles
    //
    val allEltFactories = sset.schemas.flatMap{_.schemaDocuments.flatMap{_.globalElementDecls}}
    val allElts = allEltFactories.map{_.forRoot()}
    System.err.println("Compiling " + allElts.length + " element(s).")
    val allParsers = allElts.foreach{
      elt => {
        val doc = elt.document
        // System.err.println("document = " + doc)
        val parser = doc.parser
        System.err.println("parser = " + parser)
        // str = parser.toString
        parser
      }
    }
    
    if (root == "") {
      Assert.invariant(rootNamespace == "")
      val rootElt = allElts(0) // TODO: when we generalize to multiple files, this won't work any more
      root = rootElt.name
      rootNamespace = rootElt.schemaDocument.targetNamespace
    }
    if (rootNamespace == "") {
      rootNamespace = (xml \ "@targetNamespace").text
    }
    
    val maybeRoot = allElts.find{decl => decl.namespace == rootNamespace && decl.name == root}
    val res = maybeRoot match {
      case None => Assert.usageError("The document element named " + root + " was not found.")
      case Some(rootElem) => {
        val parserFactory = rootElem.document
        val parser = parserFactory.parser // if we can get this far, that says alot.
        (sset, parser, rootElem)
      }
    }
    res
  }

  def commonCompile(xml: Node) = {
    val elts = (xml \ "element")
    Assert.usage(elts.length != 0, "No top level element declarations found.")
    //
    // New front end (validates etc. We want to exercise it.)
    //
    frontEnd(xml)
  }

  def reload(fileNameOfSavedParser: String) = {
      Assert.notYetImplemented()
//      val sp = daffodil.parser.SchemaParser.readParser(fileNameOfSavedParser)
//      backEnd(sp, Assert.notYetImplemented())
  }

  def compile(schemaFileName: String): DFDL.ProcessorFactory = {
    val schemaNode = XML.load(schemaFileName)
    compileSchema(schemaNode)
  }

  def compile(xml: Node): DFDL.ProcessorFactory = compileSchema(xml)

  private def compileSchema(xml: Node): DFDL.ProcessorFactory = {
    val (sset, parser, rootElem) = commonCompile(xml)
    newBackEnd(parser, sset, rootElem)
//      
//       old back-end....
//      
//      val sp = new daffodil.parser.SchemaParser
//      val schemaAsByteArrayStream = new ByteArrayInputStream(xml.toString.getBytes())
//      sp.parse(schemaAsByteArrayStream) // parse the schema that is.
//      backEnd(sp, sset)
  }

  def newBackEnd(parser : Parser, sset: SchemaSet, rootElem : GlobalElementDecl) = {
     new DFDL.ProcessorFactory {

      lazy val schemaSet = sset
      def onPath(xpath: String): DFDL.DataProcessor =
        new DFDL.DataProcessor {

          def save(fileName: String): Unit = {
            Assert.notYetImplemented()
          }
          
          def parse(input: DFDL.Input): scala.xml.Node = {
//            val inStream = java.nio.channels.Channels.newInputStream(input)
//            val bufferedInStream = new java.io.BufferedInputStream(inStream)
            val initialState = PState.createInitialState(rootElem, input) // also want to pass here the externally set variables, other flags/settings.
            val resultState = parser.parse(initialState)
            if (resultState.status == Success) {
            val jdomFakeRoot = resultState.parent
            // top node is this fake root element
            Assert.invariant(jdomFakeRoot.getName() == "_document_" )
            Assert.invariant(jdomFakeRoot.getContentSize() == 1)
            val jdomElt = jdomFakeRoot.getContent(0).asInstanceOf[org.jdom.Element]
            val node = XMLUtils.element2Elem(jdomElt)
            node
            }
            else
            {
              val f = resultState.status.asInstanceOf[Failure]
               throw new daffodil.exceptions.PE("Processing error at bitPos: " + resultState.bitPos + " charPos " + resultState.charPos + 
                   "\nReason Failed: " + f.msg)
            }
          }

          def unparse(output: DFDL.Output, node: scala.xml.Node): Unit = {
            val jdomElem = XMLUtils.elem2Element(node)
            val jdomDoc = new org.jdom.Document(jdomElem)
            Assert.notYetImplemented()
          }
        }

    }
  }
  
//  def backEnd(sp: daffodil.parser.SchemaParser, sset: SchemaSet) = {
//    new DFDL.ProcessorFactory {
//
//      lazy val schemaSet = sset
//      def onPath(xpath: String): DFDL.DataProcessor =
//        new DFDL.DataProcessor {
//
//          def save(fileName: String): Unit = {
//            Assert.notYetImplemented()
//            // daffodil.parser.SchemaParser.writeParser(sp, fileName)
//          }
//          
//          def parse(input: DFDL.Input): scala.xml.Node = {
//            val inStream = java.nio.channels.Channels.newInputStream(input)
//            val bufferedInStream = new java.io.BufferedInputStream(inStream)
//            sp.setDebugging(debugMode)
//            val jdomElt = sp.eval(bufferedInStream, root)
//            val node = XMLUtils.element2Elem(jdomElt)
//            node
//          }
//
//          def unparse(output: DFDL.Output, node: scala.xml.Node): Unit = {
//            sp.setDebugging(debugMode)
//            val jdomElem = XMLUtils.elem2Element(node)
//            val jdomDoc = new org.jdom.Document(jdomElem)
//            val data = sp.unparse(jdomDoc, root)
//            // it would be nicer to stream this, avoid a copy, etc.
//            // But that requires us to change the signature of sp.unparse.
//            output.write(data)
//            output
//          }
//        }
//
//    }
//  }
}


object Compiler {
  
  def apply() = new Compiler()

  /**
   * validate a DFDL schema.
   *
   * This validates the XML Schema language subset that DFDL uses, and also all the annotations
   * hung off of it.
   */
  def validateDFDLSchema(doc: Node) = {
    // TODO: should this do something other than throw an exception on a validation error?
    //
    // Users will write DFDL Schemas, using the xs or xsd prefix (usually) bound to the XML Schema namespace,
    // and the dfdl prefix (usually) bound to the DFDL namespace.
    //
    // However, we don't want to validate using the XML Schema for XML Schema (which would be the usual interpretation
    // of validating an XML Schema), instead we want to use the schema for the DFDL Subset of XML Schema.
    //
    // So, the hack here, is we're going to textually substitute the URIs, so that the validator doesn't have to be 
    // modified to do this switch, and we don't have to lie in the DFDL Subset schema, and claim it is realizing the
    // XML Schema URI.
    //
    // However, we should consider whether there is a better way to do this involving either (a) lying and having the
    // DFDL Subset Schema pretend it is the XSD schema, or we can play some catalog tricks perhaps.
    //
    // Also, the way this whole thing finds the necessary schemas is a bit daft. It should look in the jar or files,
    // but it should be using an XML Catalog.
    //
    val docstring = doc.toString()
    val xmlnsURI = "http://www.w3.org/2001/XMLSchema";
    val xsdSubsetURI = "http://www.ogf.org/dfdl/dfdl-1.0/XMLSchemaSubset";
    val docReplaced = docstring.replaceAll(xmlnsURI, xsdSubsetURI)
    val docReader = new StringReader(docReplaced)
    val schemaInputStream = Validator.daffodilLibSchema(Validator.dfdlSchemaFileName())
    val schemaReader = new InputStreamReader(schemaInputStream)
    val res = Validator.validateXMLStream(schemaReader, docReader)
    res
  }

  def stringToReadableByteChannel(s: String) = {
    val bytes = s.getBytes()
    byteArrayToReadableByteChannel(bytes)
  }
  
  def byteArrayToReadableByteChannel(bytes : Array[Byte]) = {
    val inputStream = new ByteArrayInputStream(bytes);
    val rbc = java.nio.channels.Channels.newChannel(inputStream);
    rbc
  }

  def fileToReadableByteChannel(file: java.io.File) = {
    val inputStream = new java.io.FileInputStream(file)
    val rbc = java.nio.channels.Channels.newChannel(inputStream);
    rbc
  }

  def testString(testSchema: Node, data: String) = {
    val compiler = Compiler()
    val pf = compiler.compile(testSchema)
    val p = pf.onPath("/")
    val d = Compiler.stringToReadableByteChannel(data)
    val actual = p.parse(d)
    actual
  }

  def testBinary(testSchema: Node, hexData: String) = {
    val compiler = Compiler()
    val pf = compiler.compile(testSchema)
    val p = pf.onPath("/")
    val b = hex2Bytes(hexData)
    val rbc = byteArrayToReadableByteChannel(b)
    val actual = p.parse(rbc)
    actual
  }
  
  def testFile(testSchema: Node, fileName: String) = {
    val compiler = Compiler()
    val pf = compiler.compile(testSchema)
    val p = pf.onPath("/")
    val d = Compiler.fileToReadableByteChannel(new java.io.File(fileName))
    val actual = p.parse(d)
    actual
  }
}

  // Example of using XSOM
  //
  //  def rip(schema: String) = {
  //    val parser = new XSOMParser()
  //    val apf = new DomAnnotationParserFactory()
  //    parser.setAnnotationParser(apf)
  //
  //    val instream = new ByteArrayInputStream(schema.getBytes());
  //
  //    parser.parse(instream)
  //
  //    val sset = parser.getResult()
  //    val sds = parser.getDocuments()
  //    (sds, sset)
  //  }
