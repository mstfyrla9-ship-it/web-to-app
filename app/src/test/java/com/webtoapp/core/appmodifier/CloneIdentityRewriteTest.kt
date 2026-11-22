package com.webtoapp.core.appmodifier

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.webtoapp.core.i18n.Strings
import com.webtoapp.core.playstore.aab.axml.AxmlBuilder
import com.webtoapp.core.playstore.aab.axml.AxmlToProtoXml
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CloneIdentityRewriteTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun providerManifest(authority: String, providerClass: String): ByteArray {
        val strings = listOf(
            "name",
            "authorities",
            "exported",
            "android",
            "http://schemas.android.com/apk/res/android",
            "manifest",
            "application",
            "provider",
            "com.orig.app",
            authority,
            providerClass
        )
        return AxmlBuilder().stringPool(strings)
            .resourceMap(intArrayOf(android.R.attr.name, android.R.attr.authorities, android.R.attr.exported))
            .startNamespace(3, 4)
            .startElement(-1, 5, emptyList())
            .startElement(-1, 6, emptyList())
            .startElement(-1, 7, listOf(
                AxmlBuilder.Attr(3, 0, 10, 0x03, 10),
                AxmlBuilder.Attr(3, 1, 9, 0x03, 9),
                AxmlBuilder.Attr(3, 2, -1, 0x12, 0)
            ))
            .endElement(-1, 7)
            .endElement(-1, 6)
            .endElement(-1, 5)
            .endNamespace(3, 4)
            .build()
    }

    private fun providerAttributes(axml: ByteArray): Map<String, String> {
        val provider = AxmlToProtoXml.convert(axml).element.childList
            .single { it.hasElement() && it.element.name == "application" }.element.childList
            .single { it.hasElement() && it.element.name == "provider" }.element
        return provider.attributeList.associate { it.name to it.value }
    }

    @Test
    fun `class-like provider authority is rewritten to the new package`() {
        val rewriter = CloneManifestRewriter()
        val input = providerManifest("com.orig.app.MyProvider", "com.orig.app.MyProvider")

        val result = rewriter.rewrite(input, "com.orig.app", "com.clone.app")

        val attrs = providerAttributes(result.axmlData)
        assertThat(attrs["authorities"]).isEqualTo("com.clone.app.MyProvider")
        assertThat(attrs["name"]).isEqualTo("com.orig.app.MyProvider")
    }

    @Test
    fun `multi-segment authority rewrites only original-package segments`() {
        val rewriter = CloneManifestRewriter()
        val input = providerManifest("com.orig.app.files;com.other.provider", "com.orig.app.FilesProvider")

        val result = rewriter.rewrite(input, "com.orig.app", "com.clone.app")

        assertThat(providerAttributes(result.axmlData)["authorities"])
            .isEqualTo("com.clone.app.files;com.other.provider")
    }

    @Test
    fun `clone strips every v1 signature block including ECDSA EC`() {
        val source = File(context.cacheDir, "clone-sig-source.apk")
        val manifest = AxmlBuilder().stringPool(
            listOf("android", "http://schemas.android.com/apk/res/android", "manifest", "application", "com.orig.app")
        )
            .startNamespace(0, 1)
            .startElement(-1, 2, emptyList())
            .startElement(-1, 3, emptyList())
            .endElement(-1, 3)
            .endElement(-1, 2)
            .endNamespace(0, 1)
            .build()
        ZipOutputStream(source.outputStream()).use { zip ->
            listOf(
                "AndroidManifest.xml" to manifest,
                "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
                "META-INF/CERT.SF" to "Signature-Version: 1.0\n".toByteArray(),
                "META-INF/CERT.RSA" to byteArrayOf(0x30, 0x01),
                "META-INF/CERT.DSA" to byteArrayOf(0x30, 0x02),
                "META-INF/CERT.EC" to byteArrayOf(0x30, 0x03),
                "META-INF/services/com.example.Service" to "impl\n".toByteArray(),
                "assets/keep.txt" to "keep".toByteArray()
            ).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val output = File(context.cacheDir, "clone-sig-output.apk")

        val cloner = AppCloner(context)
        val method = AppCloner::class.java.getDeclaredMethod(
            "modifyApk",
            File::class.java, File::class.java,
            String::class.java, String::class.java,
            String::class.java, String::class.java,
            android.graphics.Bitmap::class.java,
            ByteArray::class.java, String::class.java, String::class.java, String::class.java,
            Boolean::class.javaPrimitiveType,
            kotlin.jvm.functions.Function1::class.java
        )
        method.isAccessible = true
        method.invoke(
            cloner, source, output,
            "com.orig.app", "com.clone.app",
            "Orig", "Clone",
            null, null, null, null, "IMAGE",
            false,
            { _: Int -> } as Function1<Int, Unit>
        )

        val names = ZipFile(output).use { zip -> zip.entries().toList().map { it.name } }
        assertThat(names).doesNotContain("META-INF/MANIFEST.MF")
        assertThat(names).doesNotContain("META-INF/CERT.SF")
        assertThat(names).doesNotContain("META-INF/CERT.RSA")
        assertThat(names).doesNotContain("META-INF/CERT.DSA")
        assertThat(names).doesNotContain("META-INF/CERT.EC")
        assertThat(names).contains("META-INF/services/com.example.Service")
        assertThat(names).contains("assets/keep.txt")
    }

    @Test
    fun `split APK apps are rejected before clone processing`() = runBlocking {
        val app = InstalledAppInfo(
            packageName = "com.example.split",
            appName = "Split app",
            versionName = "1",
            versionCode = 1L,
            icon = null,
            apkPath = "/missing/base.apk",
            isSystemApp = false,
            installedTime = 0L,
            updatedTime = 0L,
            apkSize = 1L,
            splitApkPaths = listOf("/missing/config.apk")
        )

        val result = AppCloner(context).cloneAndInstall(
            AppModifyConfig(originalApp = app, newAppName = "Split clone")
        )

        assertThat(result).isInstanceOf(AppModifyResult.Error::class.java)
        assertThat((result as AppModifyResult.Error).message)
            .isEqualTo(Strings.appModifierSplitApkUnsupported)
    }

    @Test
    fun `clone APK share intent grants read access to a content URI`() {
        val apk = File(context.getExternalFilesDir(null) ?: context.filesDir, "cloned_apks/share.apk")
        apk.parentFile?.mkdirs()
        apk.writeBytes(byteArrayOf(1, 2, 3))

        try {
            val intent = AppModifierApkSharer.createSendIntent(context, apk)

            assertThat(intent.action).isEqualTo(Intent.ACTION_SEND)
            assertThat(intent.type).isEqualTo("application/vnd.android.package-archive")
            assertThat(intent.clipData?.getItemAt(0)?.uri?.scheme).isEqualTo("content")
            assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        } finally {
            apk.delete()
        }
    }
}
