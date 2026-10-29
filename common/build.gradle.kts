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

/*
 * The core: everything CreeperRecover does, built as creeper-recover-core.jar.
 *
 * Deployed to plugins/CreeperRecover/core/creeper-recover-core.jar (boot) or
 * creeper-recover-core.jar.staged (picked up by /recover swap or the next restart). The shell loads
 * it into a throwaway classloader whose parent is the shell's own, with parent-first delegation, so
 * any class present in both jars would always resolve from the shell and could never be replaced by
 * a swap. That is why the shell contract below is compileOnly, and why :spigot:verifyJarSplit
 * checks the built jars.
 */

plugins {
    id("java")
    id("com.gradleup.shadow") version "9.0.0-beta4"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()

    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // bStats is shaded into the core only (relocated below); each generation gets its own copy and
    // shuts its submitter down when it stops.
    implementation("org.bstats:bstats-bukkit:" + findProperty("bstats_version"))
    // Annotations are compile-time only; shading them would put the same classes in both jars.
    compileOnly("org.jetbrains:annotations:" + findProperty("jetbrains_annotations_version"))

    compileOnly("io.papermc.paper:paper-api:" + findProperty("paper_version"))

    // The shell contract (CreeperRecoverCore, CreeperShellContext). compileOnly on purpose: at
    // runtime these come from the parent loader, and a copy in this jar would be a second,
    // incompatible set of the types handed across the swap boundary.
    compileOnly(project(":spigot"))

    compileOnly("org.projectlombok:lombok:" + findProperty("lombok_version"))
    annotationProcessor("org.projectlombok:lombok:" + findProperty("lombok_version"))
}

tasks.shadowJar {
    archiveFileName.set("creeper-recover-core.jar")
    relocate("org.bstats", "de.rafael.plugins.creeper.recover.utils")
    // Fixed timestamps and order, so an unchanged build has an unchanged digest in the swap report.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
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
