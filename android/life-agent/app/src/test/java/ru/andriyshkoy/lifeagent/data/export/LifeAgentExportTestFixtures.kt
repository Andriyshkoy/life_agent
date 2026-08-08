package ru.andriyshkoy.lifeagent.data.export

import java.io.File

internal object LifeAgentExportTestFixtures {
    val repositoryRoot: File by lazy {
        val workingDirectory = checkNotNull(System.getProperty("user.dir"))
        checkNotNull(
            generateSequence(File(workingDirectory), File::getParentFile).firstOrNull {
                File(it, "schemas/life-agent-export.schema.json").isFile
            },
        ) { "Life Agent export fixtures require a full repository checkout" }
    }

    fun publicBytes(): ByteArray =
        File(repositoryRoot, "examples/life-agent-export.json").readBytes()

    fun expectedDigest(): String =
        File(repositoryRoot, "examples/life-agent-export.canonical.sha256")
            .readText(Charsets.US_ASCII)
            .trim()
            .substringBefore(' ')

    fun snapshot(): LifeAgentExportSnapshot =
        CanonicalLifeAgentExportCodec().decode(publicBytes())
}
