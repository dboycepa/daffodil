name := "daffodil"

scalaVersion in ThisBuild := "2.9.2"

parallelExecution in ThisBuild := false

logBuffered in ThisBuild := false

transitiveClassifiers := Seq("sources", "javadoc")

libraryDependencies in ThisBuild := Seq(
	"junit" % "junit" % "4.8.2",
	"org.jdom" % "jdom" % "1.1.3",
	"net.sourceforge.saxon" % "saxon" % "9.1.0.8" classifier "" classifier "dom" classifier "jdom" classifier "s9api" classifier "xpath",
	"com.ibm.icu" % "icu4j" % "4.6.1.1",// classifier "" classifier "charset" classifier "localespi",
	"org.scalatest" % "scalatest_2.9.1" % "1.6.1",
	"joda-time" % "joda-time" % "1.6",
        "xerces" % "xercesImpl" % "2.10.0",
        "xml-resolver" % "xml-resolver" % "1.2"
)

retrieveManaged := true

exportJars in ThisBuild := true
