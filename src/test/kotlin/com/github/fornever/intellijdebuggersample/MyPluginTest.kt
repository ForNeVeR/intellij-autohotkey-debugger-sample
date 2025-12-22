package com.github.fornever.intellijdebuggersample

import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase

@TestDataPath("\$CONTENT_ROOT/src/test/testData")
class MyPluginTest : BasePlatformTestCase() {

    fun testRename() {
        myFixture.testRename("foo.xml", "foo_after.xml", "a2")
    }

    override fun getTestDataPath() = "src/test/testData/rename"
}
