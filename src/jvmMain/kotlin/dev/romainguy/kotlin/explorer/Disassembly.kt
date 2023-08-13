/*
 * Copyright (C) 2023 Romain Guy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.romainguy.kotlin.explorer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.io.path.extension

fun disassemble(
    mainScope: CoroutineScope,
    toolPaths: ToolPaths,
    source: String,
    onBytecode: (String) -> Unit,
    onDex: (String) -> Unit,
    onOat: (String) -> Unit,
    onStatusUpdate: (String) -> Unit
) {
    mainScope.launch(Dispatchers.IO) {
        mainScope.launch { onStatusUpdate("Compiling Kotlin…") }

        val directory = toolPaths.tempDirectory
        cleanupClasses(directory)

        val path = directory.resolve("KotlinExplorer.kt")
        Files.writeString(path, source)

        val kotlinc = process(
            "kotlinc", path.toString(),
            directory = directory
        )

        if (kotlinc.exitCode != 0) {
            mainScope.launch { onBytecode(kotlinc.output) }
            return@launch
        }

        mainScope.launch { onStatusUpdate("Disassembling bytecode…") }

        val javap = process(
            *buildJavapCommand(directory),
            directory = directory
        )

        mainScope.launch { onBytecode(javap.output) }

        if (javap.exitCode != 0) {
            return@launch
        }

        mainScope.launch { onStatusUpdate("Optimizing with R8…") }

        writeR8Rules(directory)

        val r8 = process(
            *buildR8Command(toolPaths, directory),
            directory = directory
        )

        if (r8.exitCode != 0) {
            mainScope.launch { onDex(r8.output) }
            return@launch
        }

        mainScope.launch { onStatusUpdate("Disassembling DEX…") }

        val dexdump = process(
            toolPaths.buildToolsDirectory.resolve("dexdump").toString(),
            "-d",
            "classes.dex",
            directory = directory
        )

        mainScope.launch { onDex(dexdump.output) }

        if (dexdump.exitCode != 0) {
            return@launch
        }

        mainScope.launch { onStatusUpdate("AOT compilation…") }

        val push = process(
            "adb",
            "push",
            "classes.dex",
            "/sdcard/classes.dex",
            directory = directory
        )

        if (push.exitCode != 0) {
            mainScope.launch { onOat(push.output) }
            return@launch
        }

        val dex2oat = process(
            "adb",
            "shell",
            "dex2oat",
            "--dex-file=/sdcard/classes.dex",
            "--oat-file=/sdcard/classes.oat",
            directory = directory
        )

        if (dex2oat.exitCode != 0) {
            mainScope.launch { onOat(dex2oat.output) }
            return@launch
        }

        mainScope.launch { onStatusUpdate("Disassembling OAT…") }

        val oatdump = process(
            "adb",
            "shell",
            "oatdump",
            "--oat-file=/sdcard/classes.oat",
            directory = directory
        )

        mainScope.launch { onOat(oatdump.output) }

        if (oatdump.exitCode != 0) {
            return@launch
        }

        mainScope.launch { onStatusUpdate("Ready") }
    }
}

private fun buildR8Command(toolPaths: ToolPaths, directory: Path): Array<String> {
    val command = mutableListOf(
        "java",
        "-classpath",
        toolPaths.d8.toString(),
        "com.android.tools.r8.R8",
        "--release",
        "--pg-conf",
        "rules.txt",
        "--output",
        ".",
        "--lib",
        toolPaths.platform.toString()
    )

    toolPaths.kotlinLibs.forEach { jar ->
        command += "--lib"
        command += jar.toString()
    }

    val classFiles = Files
        .list(directory)
        .filter { path -> path.extension == "class" }
        .map { file -> file.fileName }
        .map { file -> file.toString() }
        .sorted()
        .collect(Collectors.toList())
    command.addAll(classFiles)

    return command.toTypedArray()
}

private fun writeR8Rules(directory: Path) {
    Files.writeString(
        directory.resolve("rules.txt"),
        """-keep,allowoptimization class * {
          <methods>;
        } """.trimIndent()
    )
}

private fun buildJavapCommand(directory: Path): Array<String> {
    val command = mutableListOf("javap", "-p", "-l", "-c")
    val classFiles = Files
        .list(directory)
        .filter { path -> path.extension == "class" }
        .map { file -> file.fileName }
        .map { file -> file.toString() }
        .sorted()
        .collect(Collectors.toList())
    command.addAll(classFiles)
    return command.toTypedArray()
}

private fun cleanupClasses(directory: Path) {
    Files
        .list(directory)
        .filter { path -> path.extension == "class" }
        .forEach { path -> path.toFile().delete() }
}
