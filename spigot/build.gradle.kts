/*
 * Copyright (c) 2023. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     * Redistributions of source code must retain the above copyright notice,
 *         this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *         notice, this list of conditions and the following disclaimer in the
 *         documentation and/or other materials provided with the distribution.
 *     * Neither the name of the developer nor the names of its contributors
 *         may be used to endorse or promote products derived from this software
 *         without specific prior written permission.
 *     * Redistributions in source or binary form must keep the original package
 *         and class name.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import java.util.zip.ZipFile

/*
 * The shell: plugin.yml, the CreeperRecover main class, the /recover relay and the swap protocol
 * (packages hotswap and shell). Built as creeper-recover-<version>-spigot.jar, the file name the
 * plugin has always had, so plugins/update/ can replace the installed jar. Changing anything here
 * needs a server restart; everything else is in :common (the core) and swaps with /recover swap.
 *
 * Nothing from :common may end up in this jar. The core is loaded parent-first under this jar's
 * classloader, so a core class duplicated here would always resolve from the shell and a swap would
 * silently keep running the old copy. verifyJarSplit checks that on the built jars.
 */

plugins {
    id("java")
}

evaluationDependsOn(":common")

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()

    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper API and the JDK only: no bStats, no Lombok, no annotations, and no :common.
    compileOnly("io.papermc.paper:paper-api:" + findProperty("paper_version"))
}

tasks.jar {
    archiveBaseName.set(findProperty("archives_base_name").toString())
    archiveClassifier.set(project.name)
}

val coreJar = project(":common").tasks.named<Jar>("shadowJar")

/**
 * Asserts the split the pattern rests on, against the built jars. Each failure is silent at
 * runtime: a class in both jars makes a swap a no-op, a shell class in the core gives a generation
 * its own incompatible copy of the contract, and a missing service file makes the loader refuse
 * the core at boot.
 */
val verifyJarSplit by tasks.registering {
    group = "verification"
    description = "Asserts the shell jar and the core jar carry exactly the classes they should."
    dependsOn(tasks.jar, coreJar)

    val shellFile = tasks.jar.flatMap { it.archiveFile }
    val coreFile = coreJar.flatMap { it.archiveFile }
    inputs.file(shellFile)
    inputs.file(coreFile)

    doLast {
        fun classesIn(file: File): Set<String> = ZipFile(file).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .map { it.name }
                .toSet()
        }

        val base = "de/rafael/plugins/creeper/recover/"
        val mainClass = base + "CreeperRecover"
        val shell = classesIn(shellFile.get().asFile)
        val core = classesIn(coreFile.get().asFile)

        val both = shell.intersect(core)
        if (both.isNotEmpty()) {
            throw GradleException("${both.size} class(es) are in both jars: ${both.take(10)}. They resolve "
                    + "parent-first from the shell, so a swap would keep running the shell's copy")
        }

        val strayInShell = shell.filterNot { name ->
            name == "$mainClass.class" || name.startsWith("$mainClass$")
                    || name.startsWith(base + "hotswap/") || name.startsWith(base + "shell/")
        }
        if (strayInShell.isNotEmpty()) {
            throw GradleException("shell jar contains ${strayInShell.size} class(es) outside the shell: "
                    + "${strayInShell.take(10)}. Plugin logic belongs in :common")
        }

        val strayInCore = core.filterNot { name ->
            name.startsWith(base + "common/") || name.startsWith(base + "utils/")
        }
        if (strayInCore.isNotEmpty()) {
            throw GradleException("core jar contains ${strayInCore.size} class(es) it must get from the shell "
                    + "or not carry at all: ${strayInCore.take(10)}")
        }

        ZipFile(coreFile.get().asFile).use { zip ->
            if (zip.getEntry("META-INF/services/de.rafael.plugins.creeper.recover.shell.CreeperRecoverCore") == null) {
                throw GradleException("core jar has no META-INF/services entry for CreeperRecoverCore; the "
                        + "loader would refuse it")
            }
        }
        ZipFile(shellFile.get().asFile).use { zip ->
            if (zip.getEntry("plugin.yml") == null) {
                throw GradleException("shell jar has no plugin.yml")
            }
        }

        logger.lifecycle("jar split verified: shell ${shell.size} classes, core ${core.size} classes")
    }
}

tasks.assemble {
    dependsOn(coreJar)
}

tasks.build {
    dependsOn(verifyJarSplit)
}

tasks {
    javadoc {
        options.encoding = "UTF-8"
    }
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
    compileTestJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}
