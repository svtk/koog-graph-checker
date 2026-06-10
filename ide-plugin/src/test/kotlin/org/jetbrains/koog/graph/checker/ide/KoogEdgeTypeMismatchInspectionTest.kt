package org.jetbrains.koog.graph.checker.ide

import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

@TestDataPath("\$CONTENT_ROOT/src/test/testData/inspection")
class KoogEdgeTypeMismatchInspectionTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getTestDataPath(): String = "src/test/testData/inspection"

    override fun getProjectDescriptor() = object : DefaultLightProjectDescriptor() {
        override fun getSdk(): Sdk = JavaSdk.getInstance()
            .createJdk("JDK", System.getProperty("java.home"), false)
    }

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(KoogEdgeTypeMismatchInspection())
        myFixture.copyFileToProject("KoogStubs.kt")
        myFixture.copyFileToProject("KoogStubsExtension.kt")
    }

    fun testNoErrorOnCompatibleEdge() {
        myFixture.configureByFile("koogStrategy.kt")
        myFixture.checkHighlighting(false, false, false, true)
    }

    fun testErrorOnDirectTypeMismatch() {
        myFixture.configureByFile("koogStrategyEdgeTypeMismatch.kt")
        myFixture.checkHighlighting(false, false, false, true)
    }

    fun testNoErrorOnValidTransform() {
        myFixture.configureByFile("koogStrategyValidTransform.kt")
        myFixture.checkHighlighting(false, false, false, true)
    }

    fun testErrorOnTransformTypeMismatch() {
        myFixture.configureByFile("koogStrategyTransformed.kt")
        myFixture.checkHighlighting(false, false, false, true)
    }

    fun testErrorOnIsInstanceMismatch() {
        myFixture.configureByFile("koogStrategyOnIsInstance.kt")
        myFixture.checkHighlighting(false, false, false, true)
    }

    fun testErrorOnConditionThenTransformMismatch() {
        myFixture.configureByFile("koogStrategyOnConditionTransformed.kt")
        myFixture.checkHighlighting(false, false, false, true)
    }
}
