package ru.andriyshkoy.lifeagent.data.security

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import ru.andriyshkoy.lifeagent.R

@RunWith(AndroidJUnit4::class)
class BackupRulesInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mergedManifestDisablesBackupAndCleartextTraffic() {
        val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)

        assertEquals(0, appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertEquals(0, appInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
        assertEquals(
            "ru.andriyshkoy.lifeagent.LifeAgentApplication",
            appInfo.className,
        )
    }

    @Test
    fun legacyRulesExcludeEverySupportedAppDataDomain() {
        val rules = parseExclusions(R.xml.backup_rules_legacy)

        assertEquals(EXPECTED_DOMAINS, rules.getValue(LEGACY))
        assertEquals(setOf(LEGACY), rules.keys)
    }

    @Test
    fun extractionRulesExcludeCloudAndDeviceTransferDomains() {
        val rules = parseExclusions(R.xml.data_extraction_rules)

        assertEquals(EXPECTED_DOMAINS, rules.getValue(CLOUD_BACKUP))
        assertEquals(EXPECTED_DOMAINS, rules.getValue(DEVICE_TRANSFER))
        assertEquals(setOf(CLOUD_BACKUP, DEVICE_TRANSFER), rules.keys)
    }

    private fun parseExclusions(resourceId: Int): Map<String, Set<String>> {
        val parser = context.resources.getXml(resourceId)
        val exclusions = mutableMapOf<String, MutableSet<String>>()
        var section = LEGACY

        parser.use {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            CLOUD_BACKUP,
                            DEVICE_TRANSFER,
                            -> section = parser.name

                            "exclude" -> {
                                val domain = requireNotNull(
                                    parser.getAttributeValue(null, "domain"),
                                )
                                val path = parser.getAttributeValue(null, "path")
                                assertEquals(".", path)
                                assertFalse(
                                    "Duplicate exclusion for $section/$domain",
                                    exclusions.getOrPut(section, ::mutableSetOf)
                                        .contains(domain),
                                )
                                exclusions.getValue(section).add(domain)
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (parser.name == CLOUD_BACKUP || parser.name == DEVICE_TRANSFER) {
                            section = LEGACY
                        }
                    }
                }
                parser.next()
            }
        }

        return exclusions
    }

    companion object {
        private const val LEGACY = "legacy"
        private const val CLOUD_BACKUP = "cloud-backup"
        private const val DEVICE_TRANSFER = "device-transfer"

        private val EXPECTED_DOMAINS = setOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref",
        )
    }
}
