/*
 * Minecraft Dev for IntelliJ
 *
 * https://minecraftdev.org
 *
 * Copyright (c) 2023 minecraft-dev
 *
 * MIT License
 */

package com.demonwav.mcdev.platform.quilt.creator

import com.demonwav.mcdev.creator.platformtype.ModPlatformStep
import com.demonwav.mcdev.creator.step.*
import com.demonwav.mcdev.creator.step.NewProjectWizardChainStep.Companion.nextStep
import com.demonwav.mcdev.platform.forge.inspections.sideonly.Side
import com.demonwav.mcdev.platform.quilt.util.QuiltStandardLibrariesVersions
import com.demonwav.mcdev.platform.quilt.util.QuiltVersions
import com.demonwav.mcdev.util.SemanticVersion
import com.demonwav.mcdev.util.asyncIO
import com.demonwav.mcdev.util.bindEnabled
import com.intellij.ide.users.LocalUserSettings
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.openapi.observable.util.bindBooleanStorage
import com.intellij.openapi.observable.util.bindStorage
import com.intellij.openapi.observable.util.not
import com.intellij.openapi.observable.util.transform
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.EMPTY_LABEL
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.util.IncorrectOperationException
import kotlinx.coroutines.coroutineScope

class QuiltPlatformStep(
    parent: ModPlatformStep,
) : AbstractLatentStep<Pair<QuiltVersions, QuiltStandardLibrariesVersions>>(parent) {
    override val description = "download Quilt versions"

    override suspend fun computeData() = coroutineScope {
        val quiltVersions = asyncIO { QuiltVersions.downloadData() }
        val quiltedFabricApiVersions = asyncIO { QuiltStandardLibrariesVersions.downloadData() }
        quiltVersions.await()?.let { a -> quiltedFabricApiVersions.await()?.let { b -> a to b } }
    }

    override fun createStep(data: Pair<QuiltVersions, QuiltStandardLibrariesVersions>): NewProjectWizardStep {
        val (quiltVersions, apiVersions) = data
        return QuiltVersionChainStep(this, quiltVersions, apiVersions)
            .nextStep(::QuiltEnvironmentStep)
            .nextStep(::UseMixinsStep)
            .nextStep(::ModNameStep)
            .nextStep(::LicenseStep)
            .nextStep(::QuiltOptionalSettingsStep)
            .nextStep(::QuiltBuildSystemStep)
            .nextStep(::QuiltDumbModeFilesStep)
            .nextStep(::QuiltPostBuildSystemStep)
            .nextStep(::WaitForSmartModeStep)
            .nextStep(::QuiltSmartModeFilesStep)
    }

    class Factory : ModPlatformStep.Factory {
        override val name = "Quilt"
        override fun createStep(parent: ModPlatformStep) = QuiltPlatformStep(parent)
    }
}

class QuiltVersionChainStep(
    parent: NewProjectWizardStep,
    private val quiltVersions: QuiltVersions,
    private val apiVersions: QuiltStandardLibrariesVersions,
) : AbstractMcVersionChainStep(parent, "Loader Version:", "Quilt Mappings Version:", "QFAPI/QSL Version:") {
    companion object {
        private const val LOADER_VERSION = 1
        private const val QUILT_MAPPINGS_VERSION = 2
        private const val QFAPI_VERSION = 3

        val MC_VERSION_KEY = Key.create<String>("${QuiltVersionChainStep::class.java.name}.mcVersion")
        val LOADER_VERSION_KEY = Key.create<SemanticVersion>("${QuiltVersionChainStep::class.java.name}.loaderVersion")
        val QUILT_MAPPINGS_VERSION_KEY = Key.create<String>("${QuiltVersionChainStep::class.java.name}.quiltVersion")
        val API_VERSION_KEY = Key.create<SemanticVersion>("${QuiltVersionChainStep::class.java.name}.qfapiVersion")
        val OFFICIAL_MAPPINGS_KEY = Key.create<Boolean>("${QuiltVersionChainStep::class.java.name}.officialMappings")
    }

    private val showSnapshotsProperty = propertyGraph.property(false)
        .bindBooleanStorage("${javaClass.name}.showSnapshots")
    private var showSnapshots by showSnapshotsProperty

    private val useApiProperty = propertyGraph.property(true)
        .bindBooleanStorage("${javaClass.name}.useApi")
    private var useApi by useApiProperty

    private val useOfficialMappingsProperty = propertyGraph.property(false)
        .bindBooleanStorage("${javaClass.name}.useOfficialMappings")
    private var useOfficialMappings by useOfficialMappingsProperty

    init {
        showSnapshotsProperty.afterChange { updateVersionBox() }
    }

    private val mcVersions by lazy {
        quiltVersions.game.mapIndexed { index, version ->
            QuiltMcVersion(quiltVersions.game.size - 1 - index, version.version, version.stable)
        }
    }

    override fun createComboBox(row: Row, index: Int, items: List<Comparable<*>>): Cell<VersionChainComboBox> {
        return when (index) {
            MINECRAFT_VERSION -> {
                val comboBox = super.createComboBox(row, index, items)
                row.checkBox("Show snapshots").bindSelected(showSnapshotsProperty)
                comboBox
            }
            QUILT_MAPPINGS_VERSION -> {
                val comboBox = super.createComboBox(row, index, items).bindEnabled(useOfficialMappingsProperty.not())
                row.checkBox("Use Official Mappings").bindSelected(useOfficialMappingsProperty)
                row.label(EMPTY_LABEL).bindText(
                    getVersionProperty(MINECRAFT_VERSION).transform { mcVersion ->
                        mcVersion as QuiltMcVersion
                        val matched = quiltVersions.mappings.any { it.gameVersion == mcVersion.version }
                        if (matched) {
                            EMPTY_LABEL
                        } else {
                            "Unable to match Quilt Mappings versions to Minecraft version"
                        }
                    },
                ).bindEnabled(useOfficialMappingsProperty.not()).component.foreground = JBColor.YELLOW
                comboBox
            }
            QFAPI_VERSION -> {
                val comboBox = super.createComboBox(row, index, items).bindEnabled(useApiProperty)
                row.checkBox("Use Quilted Fabric API").bindSelected(useApiProperty)
                row.label(EMPTY_LABEL).bindText(
                    getVersionProperty(MINECRAFT_VERSION).transform { mcVersion ->
                        mcVersion as QuiltMcVersion
                        val matched = apiVersions.versions.any { mcVersion.version in it.gameVersions }
                        if (matched) {
                            EMPTY_LABEL
                        } else {
                            "Unable to match QFAPI/QSL versions to Minecraft version"
                        }
                    },
                ).bindEnabled(useApiProperty).component.foreground = JBColor.YELLOW
                comboBox
            }
            else -> super.createComboBox(row, index, items)
        }
    }

    override fun getAvailableVersions(versionsAbove: List<Comparable<*>>): List<Comparable<*>> {
        return when (versionsAbove.size) {
            MINECRAFT_VERSION -> mcVersions
            LOADER_VERSION -> quiltVersions.loader
            QUILT_MAPPINGS_VERSION -> {
                val mcVersion = versionsAbove[MINECRAFT_VERSION] as QuiltMcVersion
                val filteredVersions = quiltVersions.mappings.mapNotNull { mapping ->
                    mapping.version.takeIf { mapping.gameVersion == mcVersion.version }
                }
                filteredVersions.ifEmpty { quiltVersions.mappings.map { it.version } }
            }
            QFAPI_VERSION -> {
                val mcVersion = versionsAbove[MINECRAFT_VERSION] as QuiltMcVersion
                val filteredVersions = apiVersions.versions.mapNotNull { api ->
                    api.version.takeIf { mcVersion.version in api.gameVersions }
                }
                filteredVersions.ifEmpty { apiVersions.versions.map { it.version } }
            }
            else -> throw IncorrectOperationException()
        }
    }

    override fun setupUI(builder: Panel) {
        super.setupUI(builder)
        if (!showSnapshots) {
            updateVersionBox()
        }
    }

    private fun updateVersionBox() {
        val versionBox = getVersionBox(MINECRAFT_VERSION) ?: return
        val selectedItem = versionBox.selectedItem
        versionBox.setSelectableItems(mcVersions.filter { gameVer -> showSnapshots || gameVer.stable })
        versionBox.selectedItem = selectedItem
    }

    override fun setupProject(project: Project) {
        super.setupProject(project)
        data.putUserData(MC_VERSION_KEY, (getVersion(MINECRAFT_VERSION) as QuiltMcVersion).version)
        data.putUserData(LOADER_VERSION_KEY, getVersion(LOADER_VERSION) as SemanticVersion)
        data.putUserData(QUILT_MAPPINGS_VERSION_KEY, (getVersion(QUILT_MAPPINGS_VERSION) as QuiltVersions.QuiltMappingsVersion).name)
        if (useApi) {
            data.putUserData(API_VERSION_KEY, getVersion(QFAPI_VERSION) as SemanticVersion)
        }
        data.putUserData(OFFICIAL_MAPPINGS_KEY, useOfficialMappings)
    }
}

class QuiltEnvironmentStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {
    private val environmentProperty = propertyGraph.property(Side.NONE)
    init {
        environmentProperty.transform(Side::name, Side::valueOf).bindStorage("${javaClass.name}.side")
    }
    private var environment by environmentProperty

    override fun setupUI(builder: Panel) {
        with(builder) {
            row("Environment:") {
                comboBox(listOf("Both", "Client", "Server"))
                    .bindItem(
                        environmentProperty.transform({
                            when (it) {
                                Side.CLIENT -> "Client"
                                Side.SERVER -> "Server"
                                else -> "Both"
                            }
                        }, {
                            when (it) {
                                "Client" -> Side.CLIENT
                                "Server" -> Side.SERVER
                                else -> Side.NONE
                            }
                        },),
                    )
            }
        }
    }

    override fun setupProject(project: Project) {
        data.putUserData(KEY, environment)
    }

    companion object {
        val KEY = Key.create<Side>("${QuiltEnvironmentStep::class.java.name}.environment")
    }
}

class QuiltOptionalSettingsStep(parent: NewProjectWizardStep) : AbstractCollapsibleStep(parent) {
    override val title = "Optional Settings"

    override fun createStep() = DescriptionStep(this)
        .nextStep(::QuiltAuthorsStep)
        .nextStep(::WebsiteStep)
        .nextStep(::RepositoryStep)
        .nextStep(::IssueTrackerStep)
}

class QuiltAuthorsStep(parent: NewProjectWizardStep) : AbstractOptionalStringBasedOnProjectNameStep(parent) {
    override val label = "Authors:"
    override val bindToStorage = true

    init {
        value = "${LocalUserSettings.userName}:Owner"
    }

    override fun setupProject(project: Project) {
        data.putUserData(KEY, parseAuthors(value))
    }

    companion object {
        val KEY = Key.create<List<Pair<String, String>>>("${QuiltAuthorsStep::class.java.name}.authors")

        private val braceRegex = Regex("[{}]")
        private val commaRegex = Regex("\\s*,\\s*")
        private val colonRegex = Regex("\\s*:\\s*")

        fun parseAuthors(string: String): List<Pair<String, String>> {
            return if (string.isNotBlank()) {
                string.trim()
                    .replace(braceRegex, "")
                    .split(commaRegex)
                    .map { it.split(colonRegex) }
                    .map { it[0] to it[1] }
            } else {
                emptyList()
            }
        }
    }
}
