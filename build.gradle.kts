import xyz.jpenilla.resourcefactory.bukkit.BukkitPluginYaml

plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.19"
    id("xyz.jpenilla.run-paper") version "3.0.0"
    id("xyz.jpenilla.resource-factory-bukkit-convention") version "1.3.0"
}

group = "com.scottlinder"
version = "0.1"
description = "sane"

var javaVersion = 21
var paperVersion = "1.21.10"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
}

dependencies {
    paperweight.paperDevBundle("${paperVersion}-R0.1-SNAPSHOT")
}

tasks {
    compileJava {
        options.compilerArgs.addAll(arrayOf("-Xlint:all", "-Xlint:-processing", "-Xdiags:verbose"))
        options.release = javaVersion
    }
}

bukkitPluginYaml {
  main = "com.scottlinder.sane.Sane"
  load = BukkitPluginYaml.PluginLoadOrder.STARTUP
  authors.add("Authors")
  apiVersion = paperVersion
}
