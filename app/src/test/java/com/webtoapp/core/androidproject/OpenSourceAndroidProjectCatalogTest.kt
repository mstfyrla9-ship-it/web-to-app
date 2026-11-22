package com.webtoapp.core.androidproject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSourceAndroidProjectCatalogTest {
    @Test
    fun `catalog contains curated projects with safe identifiers and repository urls`() {
        assertTrue(OpenSourceAndroidProjectCatalog.projects.isNotEmpty())

        OpenSourceAndroidProjectCatalog.projects.forEach { project ->
            assertTrue(project.id.matches(Regex("[a-z0-9-]+")))
            assertTrue(project.repositoryUrl.startsWith("https://github.com/"))
            assertFalse(project.license.isBlank())
            assertFalse(project.bestFor.isBlank())
        }
    }

    @Test
    fun `find and search catalog projects`() {
        val project = OpenSourceAndroidProjectCatalog.findById("nowinandroid")

        assertNotNull(project)
        assertEquals("Now in Android", project?.name)
        assertTrue(
            OpenSourceAndroidProjectCatalog.search("architecture").any {
                it.id == "nowinandroid"
            }
        )
        assertTrue(
            OpenSourceAndroidProjectCatalog.search("media").any {
                it.category == "media"
            }
        )
    }

    @Test
    fun `unknown project lookup is null`() {
        assertEquals(null, OpenSourceAndroidProjectCatalog.findById("not-in-the-catalog"))
    }
}
