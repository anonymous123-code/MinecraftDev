/*
 * Minecraft Dev for IntelliJ
 *
 * https://minecraftdev.org
 *
 * Copyright (c) 2023 minecraft-dev
 *
 * MIT License
 */

package com.demonwav.mcdev.platform.mcp.gradle.tooling.quiltloom

import org.gradle.api.Project
import org.jetbrains.annotations.NotNull
import org.jetbrains.plugins.gradle.tooling.ErrorMessageBuilder
import org.jetbrains.plugins.gradle.tooling.ModelBuilderService

class QuiltLoomModelBuilderImpl implements ModelBuilderService {

    @Override
    boolean canBuild(String modelName) {
        return QuiltLoomModel.name == modelName
    }

    @Override
    Object buildAll(String modelName, Project project) {
        if (!project.plugins.hasPlugin('quilt-loom')) {
            return null
        }

        def loomExtension = project.extensions.getByName('loom')

        try {
            return build(project, loomExtension)
        } catch (GroovyRuntimeException ignored) {
            // Must be using an older loom version, fallback.
            return buildLegacy(project, loomExtension)
        }
    }

    QuiltLoomModel build(Project project, Object loomExtension) {
        def tinyMappings = loomExtension.mappingsFile
        def splitMinecraftJar = loomExtension.areEnvironmentSourceSetsSplit()

        def decompilers = [:]

        if (splitMinecraftJar) {
            decompilers << ["common": getDecompilers(loomExtension, false)]
            decompilers << ["client": getDecompilers(loomExtension, true)]
        } else {
            decompilers << ["single": getDecompilers(loomExtension, false)]
        }

        //noinspection GroovyAssignabilityCheck
        return new QuiltLoomModelImpl(tinyMappings, decompilers, splitMinecraftJar)
    }

    List<QuiltLoomModelImpl.DecompilerModelImpl> getDecompilers(Object loomExtension, boolean client) {
        loomExtension.decompilerOptions.collect {
            def task = loomExtension.getDecompileTask(it, client)
            def sourcesPath = task.outputJar.get().getAsFile().getAbsolutePath()
            new QuiltLoomModelImpl.DecompilerModelImpl(name: it.name, taskName: task.name, sourcesPath: sourcesPath)
        }
    }

    QuiltLoomModel buildLegacy(Project project, Object loomExtension) {
        def tinyMappings = loomExtension.mappingsProvider.tinyMappings.toFile().getAbsoluteFile()
        def decompilers = loomExtension.decompilerOptions.collect {
            def task = project.tasks.getByName('genSourcesWith' + it.name.capitalize())
            def sourcesPath = task.runtimeJar.get().getAsFile().getAbsolutePath().dropRight(4) + "-sources.jar"
            new QuiltLoomModelImpl.DecompilerModelImpl(name: it.name, taskName: task.name, sourcesPath: sourcesPath)
        }

        //noinspection GroovyAssignabilityCheck
        return new QuiltLoomModelImpl(tinyMappings, ["single": decompilers], false)
    }

    @Override
    ErrorMessageBuilder getErrorMessageBuilder(@NotNull Project project, @NotNull Exception e) {
        return ErrorMessageBuilder.create(
                project, e, "MinecraftDev import errors"
        ).withDescription("Unable to build MinecraftDev QuiltLoom project configuration")
    }
}