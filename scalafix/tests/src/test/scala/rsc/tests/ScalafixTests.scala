package rsc.tests

import scalafix.testkit._

class ScalafixTests extends SemanticRuleSuite {
  runAllTests()
//  testsToRun.filter(_.path.testName.contains("BetterRscCompat")).map(runOn)
}
